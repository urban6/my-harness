#!/usr/bin/env python3
"""보충 판정 2 — 스킬과 무관한 '일반 코드 품질'을 run별로 비교한다 (2차 run 9개).

judge_convention.py는 스킬 체크리스트 기준이라 스킬을 지시받은 B에 유리한 순환 측정이었다.
이 판정은 기준을 스킬 밖에서 가져온다.
- 기준: 일반적인 코드 리뷰 관점(가독성·중복·죽은 코드·예외 처리·산술 안전·시간 주입·상태 전이 응집·외부 호출 타임아웃 등)
  G1~G14. spring-boot 스킬 본문·참조 문서에 있는 내용(레이어링·DI·DTO·트랜잭션·ProblemDetail·민감정보 로그·
  설정 바인딩 등)은 뺐다.
- 입력·블라인드·반복·집계는 judge_convention.py와 같다. 라벨은 새로 섞는다(Z1~Z9).

출력: results/general-quality/{label}-p{n}.json, results/general-quality/blind-key.json, results/general-quality.json
사용법: judge_general.py [--passes 3] [--model claude-opus-5-5]
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
OUT = os.path.join(PLAYGROUND, "results", "general-quality")
RUNS = [f"{g}-{i}" for g in "ABC" for i in (1, 2, 3)]
ITEMS = {
    "G1": "한 메서드에 여러 단계를 몰아 담아 50줄을 넘는 메서드가 없다.",
    "G2": "같은 로직(검증·매핑·계산·조회 후 없으면 예외 등)이 여러 곳에 복사되어 있지 않고 한 곳에서 재사용된다.",
    "G3": "여러 곳에서 쓰는 의미 있는 값(에러 코드·상태 이름·한도값 등)이 문자열·숫자 리터럴로 흩어져 있지 않고 상수나 enum으로 정의되어 있다.",
    "G4": "이름이 의도를 드러낸다 — 한 글자·의미 없는 약어·모호한 이름(data, tmp, info, process, handle 등)으로 된 변수·메서드가 거의 없다.",
    "G5": "죽은 코드가 없다 — 주석 처리된 코드, 쓰이지 않는 메서드·필드·클래스·import가 없다.",
    "G6": "예외를 삼키지 않는다 — 빈 catch, 잡고 무시, catch(Exception)로 넓게 잡아 원인을 잃는 코드가 없다.",
    "G7": "금액·수량 산술이 안전하다 — double/float로 금액을 계산하지 않고, 곱셈·합산에 오버플로 대책(Math.*Exact, BigDecimal, 사전 범위 검증 등)이 있다.",
    "G8": "현재 시각을 주입 가능한 의존(Clock 등)으로 얻는다. 서비스·도메인 로직 곳곳에서 Instant.now()/LocalDateTime.now()를 직접 호출하면 위반.",
    "G9": "주문 상태 전이 규칙(어떤 상태에서 어떤 상태로 갈 수 있는가)이 한 곳(도메인 메서드·enum·상태 표)에 모여 있다. 여러 서비스 메서드에서 상태를 제각각 if로 검사하고 대입하면 위반.",
    "G10": "외부 HTTP 호출(결제 대행사 등)에 연결·읽기 타임아웃이 명시적으로 설정되어 있다. 외부 호출이 없으면 해당 없음.",
    "G11": "SQL·JPQL을 직접 쓰는 곳은 파라미터 바인딩만 쓰고 입력값을 문자열로 이어 붙이지 않는다. 직접 쓴 쿼리가 없으면 해당 없음.",
    "G12": "한 클래스에 책임이 과도하게 몰리지 않는다 — 400줄이 넘거나 서로 다른 도메인(상품·쿠폰·주문·결제 등)을 한 클래스가 모두 처리하는 클래스가 없다.",
    "G13": "주석이 코드와 어긋나지 않는다 — 실제 동작과 다른 낡은 주석, 방치된 TODO/FIXME가 없다.",
    "G14": "도메인 객체의 필드가 아무 곳에서나 바뀌지 않는다 — 불필요한 public setter가 없고, 값 변경은 의도를 드러내는 메서드나 새 객체 생성으로 이뤄진다.",
}
REDACT = [(re.compile(p, re.I), r) for p, r in [
    (r"spring-boot\s*스킬|스킬|skill", "-"), (r"test-writer|feature-pm|backend-(designer|impl)|boundary-verifier", "작업자"),
    (r"Phase\s*\d", "단계"), (r"_workspace", "docs"), (r"orchestrat\w*|harness|하네스", "-"),
]]
SYSTEM = "너는 Java 백엔드 코드 리뷰어다. 주어진 코드에 있는 것만 근거로 판정한다. 추측하지 않는다."
INSTRUCTION = """아래 [점검 항목] G1 ~ G14 각각에 대해 [코드]가 지키는지 판정해줘.

판정 값
- compliant: 항목을 지킨다.
- violation: 항목을 어기는 코드가 하나 이상 있다.
- not_applicable: 항목이 다루는 대상이 코드에 없다 (항목에 해당 없음 조건이 적혀 있을 때만).

evidence에는 근거 파일 경로(와 클래스·메서드)를 한 줄로 적는다. violation이면 어기는 위치를 적는다.

[점검 항목]
{items}

[코드]
{code}
"""
SCHEMA = {
    "type": "object",
    "properties": {q: {
        "type": "object",
        "properties": {
            "verdict": {"type": "string", "enum": ["compliant", "violation", "not_applicable"]},
            "evidence": {"type": "string"},
        },
        "required": ["verdict", "evidence"],
    } for q in ITEMS},
    "required": list(ITEMS),
}


def code_text(run):
    root = os.path.join(PLAYGROUND, "runs", run)
    parts = [f"=== build.gradle ===\n{open(os.path.join(root, 'build.gradle')).read()}"]
    for dirpath, _, files in sorted(os.walk(os.path.join(root, "src", "main"))):
        for f in sorted(files):
            if not f.endswith((".java", ".kt", ".yml", ".yaml", ".properties", ".sql")):
                continue
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
    items = "\n".join(f"- {q}. {t}" for q, t in ITEMS.items())
    prompt = INSTRUCTION.format(items=items, code=code_text(run))
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
    ap.add_argument("--seed", type=int, default=20261012)
    ap.add_argument("--model", default="claude-opus-5-5")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    key_path = os.path.join(OUT, "blind-key.json")
    if os.path.exists(key_path):
        key = json.load(open(key_path))
    else:
        shuffled = RUNS[:]
        random.Random(a.seed).shuffle(shuffled)
        key = {f"Z{i + 1}": r for i, r in enumerate(shuffled)}
        json.dump(key, open(key_path, "w"), indent=2)

    workdir = tempfile.mkdtemp(prefix="judge-general-")
    jobs = [(label, run, n) for label, run in key.items() for n in range(1, a.passes + 1)]
    raw = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for label, n, data in ex.map(lambda j: judge(j[0], j[1], j[2], a.model, workdir), jobs):
            raw.setdefault(label, {})[n] = data
            print(f"[general] {label} p{n} done (${data.get('cost_usd') or 0:.2f})", file=sys.stderr)

    result = {}
    for label, run in key.items():
        passes = [raw[label][n]["verdict"] for n in sorted(raw[label])]
        items = {}
        for q in ITEMS:
            votes = [p[q]["verdict"] for p in passes]
            v = max(("compliant", "violation", "not_applicable"), key=votes.count)
            items[q] = {"verdict": v, "votes": votes,
                        "evidence": next(p[q]["evidence"] for p in passes if p[q]["verdict"] == v)}
        ok = sum(i["verdict"] == "compliant" for i in items.values())
        bad = sum(i["verdict"] == "violation" for i in items.values())
        result[run] = {"label": label, "compliant": ok, "violation": bad, "rate": round(ok / (ok + bad), 4),
                       "items": items}
    json.dump(result, open(os.path.join(PLAYGROUND, "results", "general-quality.json"), "w"), ensure_ascii=False, indent=2)

    print("| run | 준수 | 위반 | 준수율 | 위반 항목 |\n|---|---|---|---|---|")
    for run in RUNS:
        r = result[run]
        bad = " ".join(q for q, i in r["items"].items() if i["verdict"] == "violation")
        print(f"| {run} | {r['compliant']} | {r['violation']} | {r['rate']:.0%} | {bad or '-'} |")
    print("\n| 군 | 준수율 평균 (최소~최대) |\n|---|---|")
    for g in "ABC":
        rates = [result[f"{g}-{i}"]["rate"] for i in (1, 2, 3)]
        print(f"| {g} | {sum(rates) / 3:.0%} ({min(rates):.0%}~{max(rates):.0%}) |")
    print("\n| 항목 | A 위반 | B 위반 | C 위반 |\n|---|---|---|---|")
    for q in ITEMS:
        cnt = [sum(result[f"{g}-{i}"]["items"][q]["verdict"] == "violation" for i in (1, 2, 3)) for g in "ABC"]
        if any(cnt):
            print(f"| {q} | {cnt[0]}/3 | {cnt[1]}/3 | {cnt[2]}/3 |")
    total = sum(d.get("cost_usd") or 0 for r in raw.values() for d in r.values())
    print(f"\n총 비용 ${total:.2f}")


if __name__ == "__main__":
    main()
