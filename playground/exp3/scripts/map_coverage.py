#!/usr/bin/env python3
"""3단계 매핑 — run별 자체 테스트가 견고성 항목(S1~S25, PRD §5)을 검증하는지 LLM이 판정한다 (3차 PRD §4 테스트 품질).

- 블라인드: run 이름을 무작위 라벨(X1~X4)로 바꾸고, 테스트 코드에서 하니스 흔적 단어를 지운다.
- 중립: 도구 없는 단일 호출, 하니스·군 정보 없는 시스템 프롬프트.
- 반복: 라벨마다 PASSES회 독립 판정 → 규칙별 다수결.

출력
  results/coverage/blind-key.json    라벨 ↔ run
  results/coverage/{label}-p{n}.json 판정 원본
  results/coverage-map.json          {run: ["S1", ...]}  (aggregate.py 입력)
  results/coverage/votes.json        규칙별 득표·근거 테스트

사용법: map_coverage.py [--passes 3] [--seed 20261010] [--model claude-opus-5-5]
"""
import argparse
import json
import os
import random
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PLAYGROUND, "results", "coverage")
PRD = os.path.join(PLAYGROUND, "PRD.md")
REQS = re.findall(r"^- (S\d+)\.", open(PRD).read(), re.M)
REDACT = [(re.compile(p, re.I), r) for p, r in [
    (r"test-writer", "후속 작업"), (r"Phase\s*\d", "후속 단계"),
    (r"feature-pm|backend-(designer|impl)|boundary-verifier", "작업자"),
    (r"_workspace", "docs"), (r"orchestrat\w*|harness", "-"), (r"spring-boot\s*스킬|스킬|skill", "-"),
]]

SYSTEM = (
    "너는 테스트 코드 리뷰어다. 주어진 요구사항 명세와 테스트 코드만 보고 판정한다. "
    "추측하지 말고 코드에 있는 것만 근거로 삼는다."
)
INSTRUCTION = """아래 [요구사항]에 번호가 붙은 항목(S1 ~ S25) 각각에 대해, [테스트 코드]에 그 규칙을 검증하는 테스트가 있는지 판정해줘.

판정 기준
- covered=true: 그 규칙이 말하는 동작을 실제로 실행하고, 규칙의 핵심 결과를 단언(assert)하는 테스트가 하나 이상 있다.
- 테스트 이름·주석·표시(R1.1 등)만으로 판단하지 말고, 실행하는 동작과 단언 내용으로 판단한다.
- 규칙 고유의 내용을 단언해야 한다. 예: 검증 규칙은 잘못된 값이 거절(4xx)되는지 단언해야 하고, 상태·재고·쿠폰 변화 규칙은 변화 후 값을 단언해야 하며, 동시성 규칙은 실제 동시 요청으로 결과를 단언해야 한다. 정상 흐름에서 상태코드만 확인하는 것은 세부 규칙의 근거가 아니다.
- 규칙이 여러 조건을 나열하면, 그중 하나 이상을 실제로 검증할 때 covered=true다.
- 외부 PG는 테스트 대역(목·스텁)으로 대체해도 된다.
- tests에는 근거가 되는 테스트를 "클래스명#메서드명"으로 최대 3개 적는다.

[요구사항]
{requirements}

[테스트 코드]
{tests}
"""
SCHEMA = {
    "type": "object",
    "properties": {r: {
        "type": "object",
        "properties": {
            "covered": {"type": "boolean"},
            "tests": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["covered", "tests"],
    } for r in REQS},
    "required": REQS,
}


def requirements_text():
    text = open(PRD).read()
    start = text.index("### 입력 검증")
    end = text.index("## 6.")
    legend = ("판정 기호: 거절 = 4xx 응답(5xx 아님) · 불변 = 관련 상품 stock/reserved, 쿠폰 usedCount, "
              "주문 status가 요청 전과 같음")
    return legend + "\n\n" + text[start:end].strip()


def tests_text(run):
    root = os.path.join(PLAYGROUND, "runs", run, "src", "test")
    parts = []
    for dirpath, _, files in sorted(os.walk(root)):
        for f in sorted(files):
            path = os.path.join(dirpath, f)
            body = open(path, errors="replace").read()
            for pat, rep in REDACT:
                body = pat.sub(rep, body)
            parts.append(f"=== {os.path.relpath(path, root)} ===\n{body}")
    return "\n\n".join(parts)


def judge(label, run, n, model, workdir):
    out = os.path.join(OUT, f"{label}-p{n}.json")
    if os.path.exists(out):
        return label, n, json.load(open(out))
    prompt = INSTRUCTION.format(requirements=requirements_text(), tests=tests_text(run))
    cmd = ["claude", "-p", "--model", model, "--tools", "", "--system-prompt", SYSTEM,
           "--json-schema", json.dumps(SCHEMA), "--output-format", "json",
           "--no-session-persistence", "--strict-mcp-config"]
    res = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=workdir, timeout=1800)
    if res.returncode != 0:
        raise RuntimeError(f"{label} p{n}: exit {res.returncode}: {res.stderr[-500:]}")
    envelope = json.loads(res.stdout)
    verdict = envelope.get("structured_output")
    if verdict is None:
        verdict = json.loads(re.search(r"\{.*\}", envelope["result"], re.S).group(0))
    data = {"verdict": verdict, "cost_usd": envelope.get("total_cost_usd")}
    json.dump(data, open(out, "w"), ensure_ascii=False, indent=2)
    return label, n, data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--passes", type=int, default=3)
    ap.add_argument("--seed", type=int, default=20261009)
    ap.add_argument("--model", default="claude-opus-5-5")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    runs = sorted(d for d in os.listdir(os.path.join(PLAYGROUND, "runs")) if re.fullmatch(r"[ABC]-\d+", d))
    key_path = os.path.join(OUT, "blind-key.json")
    if os.path.exists(key_path):
        key = json.load(open(key_path))
        # 나중에 추가된 run(보충 B 군)은 기존 라벨을 유지한 채 새 라벨로 덧붙인다 — 기존 판정 재사용
        new = [r for r in runs if r not in key.values()]
        random.Random(a.seed).shuffle(new)
        for r in new:
            key[f"X{len(key) + 1}"] = r
        json.dump(key, open(key_path, "w"), indent=2)
    else:
        shuffled = runs[:]
        random.Random(a.seed).shuffle(shuffled)
        key = {f"X{i + 1}": r for i, r in enumerate(shuffled)}
        json.dump(key, open(key_path, "w"), indent=2)

    workdir = tempfile.mkdtemp(prefix="map-coverage-")
    jobs = [(label, run, n) for label, run in key.items() for n in range(1, a.passes + 1)]
    results = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for label, n, data in ex.map(lambda j: judge(j[0], j[1], j[2], a.model, workdir), jobs):
            results.setdefault(label, {})[n] = data
            print(f"[map] {label} p{n} done (${data.get('cost_usd') or 0:.2f})", file=sys.stderr)

    coverage_map, votes = {}, {}
    for label, run in key.items():
        passes = [results[label][n]["verdict"] for n in sorted(results[label])]
        votes[run] = {"label": label}
        covered = []
        for r in REQS:
            yes = sum(1 for p in passes if p[r]["covered"])
            votes[run][r] = {"yes": yes, "of": len(passes),
                             "tests": sorted({t for p in passes for t in p[r]["tests"]})}
            if yes * 2 > len(passes):
                covered.append(r)
        coverage_map[run] = covered

    json.dump(votes, open(os.path.join(OUT, "votes.json"), "w"), ensure_ascii=False, indent=2)
    json.dump(coverage_map, open(os.path.join(PLAYGROUND, "results", "coverage-map.json"), "w"), indent=2)
    total = sum(d.get("cost_usd") or 0 for r in results.values() for d in r.values())
    print(f"\n| run | 커버 | 미커버 규칙 (득표) |\n|---|---|---|")
    for run in runs:
        v = votes[run]
        miss = " ".join(f"{r}({v[r]['yes']}/{v[r]['of']})" for r in REQS if r not in coverage_map[run])
        print(f"| {run} | {len(coverage_map[run])}/{len(REQS)} | {miss or '-'} |")
    print(f"\n총 비용 ${total:.2f}")


if __name__ == "__main__":
    main()
