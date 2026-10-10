#!/usr/bin/env python3
"""3단계 단계별 누락 추적 (PRD §6) — C run의 설계 산출물이 견고성 항목(S1~S25)을 어떻게 결정했는지 LLM이 판정하고,
숨긴 테스트 결과·자체 테스트 매핑과 합쳐 항목마다 빠진 위치를 분류한다.

판정(설계 문서만 근거): matches = 항목의 기대와 같은 결정을 명시 / contradicts = 다른 결정을 명시 / absent = 결정 없음
분류
  설계 누락      숨긴 테스트 실패 + absent          → backend-designer·feature-pm 요구 분해
  설계 결정 상이  숨긴 테스트 실패 + contradicts     → backend-designer 판단 기준
  구현 누락      숨긴 테스트 실패 + matches         → boundary-verifier 점검 항목
  테스트 누락    숨긴 테스트 통과 + 자체 테스트 미검증 → test-writer 지시
  우연 통과      숨긴 테스트 통과 + absent          (설계 근거 없이 통과 — 프레임워크 기본값 등)
  정상          숨긴 테스트 통과 + 설계 명시 + 자체 테스트 검증

입력: runs/C-*/_workspace/features/*/{00_requirements.json,01_api_design.md,02_db_design.md},
      results/{run}.json, results/coverage-map.json
출력: results/trace/{run}-p{n}.json 판정 원본, results/trace.json, 표준 출력에 Markdown 표

사용법: trace_design.py [--passes 3] [--model claude-opus-5-5]
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from map_coverage import REDACT, REQS, requirements_text  # noqa: E402

PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(PLAYGROUND, "results")
OUT = os.path.join(RESULTS, "trace")
DESIGN_FILES = ("00_requirements.json", "01_api_design.md", "02_db_design.md")

SYSTEM = "너는 설계 문서 리뷰어다. 주어진 설계 문서에 적힌 것만 근거로 판정한다. 추측하지 않는다."
INSTRUCTION = """아래 [항목] S1 ~ S25 각각에 대해, [설계 문서]가 그 상황을 어떻게 결정했는지 판정해줘.

판정 값
- matches: 설계 문서가 항목의 기대와 같은 동작을 명시적으로 정했다 (예: 그 입력을 거절, 그 상황에서 복원).
- contradicts: 설계 문서가 그 상황을 명시적으로 다루지만 항목의 기대와 다른 동작으로 정했다 (예: 허용, 복원하지 않음).
- absent: 설계 문서에 그 상황에 대한 결정이 없다.

기준
- 항목에 하위 조건이 여러 개면, 모든 하위 조건이 matches일 때만 matches다. 하나라도 contradicts면 contradicts, 그 밖에 하나라도 빠지면 absent다.
- 항목이 "A 또는 B 인정"이라고 하면 A나 B 중 하나를 명시하면 matches다.
- evidence에는 근거가 된 설계 문서 문장을 한 줄로 인용한다(없으면 빈 문자열). contradicts·absent면 note에 무엇이 다르거나 빠졌는지 한 줄로 적는다.

[항목]
{items}

[설계 문서]
{design}
"""
SCHEMA = {
    "type": "object",
    "properties": {r: {
        "type": "object",
        "properties": {
            "decision": {"type": "string", "enum": ["matches", "contradicts", "absent"]},
            "evidence": {"type": "string"},
            "note": {"type": "string"},
        },
        "required": ["decision", "evidence", "note"],
    } for r in REQS},
    "required": REQS,
}


def design_text(run):
    parts = []
    for name in DESIGN_FILES:
        for path in sorted(glob.glob(os.path.join(PLAYGROUND, "runs", run, "_workspace", "features", "*", name))):
            body = open(path, errors="replace").read()
            for pat, rep in REDACT:
                body = pat.sub(rep, body)
            parts.append(f"=== {name} ===\n{body}")
    if not parts:
        raise SystemExit(f"no design artifacts for {run}")
    return "\n\n".join(parts)


def judge(run, n, model, workdir):
    out = os.path.join(OUT, f"{run}-p{n}.json")
    if os.path.exists(out):
        return run, n, json.load(open(out))
    prompt = INSTRUCTION.format(items=requirements_text(), design=design_text(run))
    cmd = ["claude", "-p", "--model", model, "--tools", "", "--system-prompt", SYSTEM,
           "--json-schema", json.dumps(SCHEMA), "--output-format", "json",
           "--no-session-persistence", "--strict-mcp-config"]
    res = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=workdir, timeout=1800)
    if res.returncode != 0:
        raise RuntimeError(f"{run} p{n}: exit {res.returncode}: {res.stderr[-500:]}")
    envelope = json.loads(res.stdout)
    verdict = envelope.get("structured_output")
    if verdict is None:
        verdict = json.loads(re.search(r"\{.*\}", envelope["result"], re.S).group(0))
    data = {"verdict": verdict, "cost_usd": envelope.get("total_cost_usd")}
    json.dump(data, open(out, "w"), ensure_ascii=False, indent=2)
    return run, n, data


def classify(passed, decision, tested):
    if not passed:
        return {"absent": "설계 누락", "contradicts": "설계 결정 상이", "matches": "구현 누락"}[decision]
    if not tested:
        return "테스트 누락"
    if decision == "absent":
        return "우연 통과"
    return "정상"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--passes", type=int, default=3)
    ap.add_argument("--model", default="claude-opus-5-5")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    runs = sorted(d for d in os.listdir(os.path.join(PLAYGROUND, "runs")) if re.fullmatch(r"C-\d+", d))
    coverage_map = json.load(open(os.path.join(RESULTS, "coverage-map.json")))

    workdir = tempfile.mkdtemp(prefix="trace-design-")
    jobs = [(run, n) for run in runs for n in range(1, a.passes + 1)]
    raw = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for run, n, data in ex.map(lambda j: judge(j[0], j[1], a.model, workdir), jobs):
            raw.setdefault(run, {})[n] = data
            print(f"[trace] {run} p{n} done (${data.get('cost_usd') or 0:.2f})", file=sys.stderr)

    trace = {}
    for run in runs:
        grade = json.load(open(os.path.join(RESULTS, f"{run}.json")))
        passed_items = set(grade["acceptance"]["passed_items"])
        passes = [raw[run][n]["verdict"] for n in sorted(raw[run])]
        trace[run] = {}
        for item in REQS:
            votes = [p[item]["decision"] for p in passes]
            decision = max(("matches", "contradicts", "absent"), key=votes.count)  # 동률이면 matches 쪽 우선
            pick = next(p[item] for p in passes if p[item]["decision"] == decision)
            passed, tested = item in passed_items, item in coverage_map.get(run, [])
            trace[run][item] = {"passed": passed, "tested": tested, "decision": decision, "votes": votes,
                                "evidence": pick["evidence"], "note": pick["note"],
                                "class": classify(passed, decision, tested)}
    json.dump(trace, open(os.path.join(RESULTS, "trace.json"), "w"), ensure_ascii=False, indent=2)

    total = sum(d.get("cost_usd") or 0 for r in raw.values() for d in r.values())
    print("| 항목 | " + " | ".join(runs) + " |")
    print("|---|" + "---|" * len(runs))
    for item in REQS:
        cells = []
        for run in runs:
            t = trace[run][item]
            cells.append(f"{t['class']} ({t['decision']}, {'통과' if t['passed'] else '실패'}, "
                         f"{'테스트 O' if t['tested'] else '테스트 X'})")
        print(f"| {item} | " + " | ".join(cells) + " |")
    print(f"\n총 비용 ${total:.2f}")


if __name__ == "__main__":
    main()
