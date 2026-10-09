#!/usr/bin/env python3
"""run별 채점 결과를 100점으로 환산하고 군별로 요약한다 (PRD §1 표, §4.1 배점).

입력
  results/{run}.json          grade.sh 산출
  runs/{run}/run-meta.json    run.sh(analyze_session.py) 산출
  results/coverage-map.json   요구사항 매핑 {run: ["R1", ...]}
출력
  results/runs.csv, results/summary.csv, 표준 출력에 Markdown 표

사용법: aggregate.py [--runs A-1 A-2 ...]   (생략 시 runs/ 아래 [ABC]-숫자 디렉터리 전부)
"""
import argparse
import csv
import json
import os
import re
from statistics import mean

PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(PLAYGROUND, "results")
REQS = [f"R{i}" for i in range(1, 9)]
GROUP_NAMES = {"A": "A 일반", "B": "B 스킬", "C": "C 오케스트레이션"}


def load(path, default=None):
    return json.load(open(path)) if os.path.exists(path) else default


def score(run, coverage_map):
    g = load(os.path.join(RESULTS, f"{run}.json"))
    meta = load(os.path.join(PLAYGROUND, "runs", run, "run-meta.json"), {})
    if g is None:
        raise SystemExit(f"missing grade result for {run}")
    acc = g.get("acceptance") or {}
    pass_rate = acc.get("pass_rate", 0.0) if g.get("boot_ok") else 0.0
    covered = [r for r in coverage_map.get(run, []) if r in REQS]
    branch = g["coverage"]["branch_coverage"]

    accuracy = 60 * pass_rate
    test_quality = 15 * len(covered) / len(REQS) + 15 * branch
    completeness = 5 * g["assemble_ok"] + 5 * g["tests"]["ok"]
    return {
        "run": run,
        "group": run.split("-")[0],
        "accuracy": round(accuracy, 2),
        "test_quality": round(test_quality, 2),
        "completeness": completeness,
        "total": round(accuracy + test_quality + completeness, 2),
        "pass_rate": pass_rate,
        "acceptance_passed": acc.get("passed", 0),
        "acceptance_total": acc.get("total", 0),
        "requirements_met": " ".join(acc.get("requirements_met", [])),
        "req_covered_by_tests": len(covered),
        "branch_coverage": branch,
        "assemble_ok": g["assemble_ok"],
        "self_tests_ok": g["tests"]["ok"],
        "self_tests": g["tests"]["tests"],
        "test_rerun": g["test_rerun"],
        "boot_ok": g["boot_ok"],
        "failure_categories": json.dumps(acc.get("failure_categories", {}), ensure_ascii=False),
        "status": meta.get("status"),
        "cost_usd": meta.get("cost_usd"),
        "minutes": round(meta.get("wall_seconds", 0) / 60, 1) if meta else None,
        "compactions": meta.get("compactions"),
        "discard": meta.get("discard"),
    }


def fmt(values, digits=1):
    vals = [v for v in values if v is not None]
    if not vals:
        return "-"
    if len(vals) == 1:
        return f"{vals[0]:.{digits}f}"
    return f"{mean(vals):.{digits}f} ({min(vals):.{digits}f}~{max(vals):.{digits}f})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", nargs="*")
    a = ap.parse_args()
    runs = a.runs or sorted(d for d in os.listdir(os.path.join(PLAYGROUND, "runs")) if re.fullmatch(r"[ABC]-\d+", d))
    coverage_map = load(os.path.join(RESULTS, "coverage-map.json"), {})
    rows = [score(r, coverage_map) for r in runs]
    kept = [r for r in rows if not r["discard"]]

    with open(os.path.join(RESULTS, "runs.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    summary = []
    for g in "ABC":
        rs = [r for r in kept if r["group"] == g]
        if not rs:
            continue
        summary.append({
            "group": GROUP_NAMES[g],
            "n": len(rs),
            "accuracy": fmt([r["accuracy"] for r in rs]),
            "test_quality": fmt([r["test_quality"] for r in rs]),
            "completeness": fmt([r["completeness"] for r in rs]),
            "total": fmt([r["total"] for r in rs]),
            "cost_usd": fmt([r["cost_usd"] for r in rs], 2),
            "minutes": fmt([r["minutes"] for r in rs]),
        })
    if summary:
        with open(os.path.join(RESULTS, "summary.csv"), "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(summary[0].keys()))
            w.writeheader()
            w.writerows(summary)

    print("| 군 | n | 정확도 (60) | 테스트 품질 (30) | 완료성 (10) | **총점 (100)** | 비용 ($) | 시간 (분) |")
    print("|---|---|---|---|---|---|---|---|")
    for s in summary:
        print(f"| {s['group']} | {s['n']} | {s['accuracy']} | {s['test_quality']} | {s['completeness']} | "
              f"**{s['total']}** | {s['cost_usd']} | {s['minutes']} |")
    print("\n| run | 총점 | 정확도 | 테스트 | 완료성 | 인수 테스트 | 실패 유형 | 상태 | 비용 | 분 | 폐기 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['run']} | {r['total']} | {r['accuracy']} | {r['test_quality']} | {r['completeness']} | "
              f"{r['acceptance_passed']}/{r['acceptance_total']} | {r['failure_categories']} | {r['status']} | "
              f"{r['cost_usd']} | {r['minutes']} | {r['discard']} |")


if __name__ == "__main__":
    main()
