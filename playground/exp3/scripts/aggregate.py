#!/usr/bin/env python3
"""run별 채점 결과를 100점으로 환산하고 군별로 요약한다 (3차 PRD §4 배점).

입력
  results/{run}.json          grade.sh 산출
  runs/{run}/run-meta.json    run.sh(analyze_session.py) 산출
  results/coverage-map.json   견고성 항목 매핑 {run: ["S1", ...]}
출력
  results/runs.csv, results/summary.csv, 표준 출력에 Markdown 표

사용법: aggregate.py [--runs A-1 A-2 ...]   (생략 시 runs/ 아래 [AC]-숫자 디렉터리 전부)
"""
import argparse
import csv
import json
import os
import re
from statistics import mean

PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(PLAYGROUND, "results")
RULES = re.findall(r"^- (S\d+)\.", open(os.path.join(PLAYGROUND, "PRD.md")).read(), re.M)
GROUP_NAMES = {"A": "A 일반", "C": "C 오케스트레이션"}


def load(path, default=None):
    return json.load(open(path)) if os.path.exists(path) else default


def score(run, coverage_map):
    g = load(os.path.join(RESULTS, f"{run}.json"))
    meta = load(os.path.join(PLAYGROUND, "runs", run, "run-meta.json"), {})
    if g is None:
        raise SystemExit(f"missing grade result for {run}")
    acc = g.get("acceptance") or {}
    covered = [r for r in coverage_map.get(run, []) if r in RULES]
    contract = 20 * acc.get("contract_rate", 0.0)
    robustness = 50 * acc.get("robustness_rate", 0.0)
    test_quality = 20 * len(covered) / len(RULES)
    completeness = 5 * g["assemble_ok"] + 5 * g["tests"]["ok"]
    return {
        "run": run,
        "group": run.split("-")[0],
        "contract": round(contract, 2),
        "robustness": round(robustness, 2),
        "test_quality": round(test_quality, 2),
        "completeness": completeness,
        "total": round(contract + robustness + test_quality + completeness, 2),
        "failed_items": " ".join(acc.get("failed_items", [])),
        "category_rates": json.dumps(acc.get("category_rates", {}), ensure_ascii=False),
        "items_covered_by_tests": len(covered),
        "assemble_ok": g["assemble_ok"],
        "self_tests_ok": g["tests"]["ok"],
        "self_tests": g["tests"]["tests"],
        "test_rerun": g["test_rerun"],
        "boot_ok": g["boot_ok"],
        "boot_ttl_ok": g.get("boot_ttl_ok"),
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
    runs = a.runs or sorted(d for d in os.listdir(os.path.join(PLAYGROUND, "runs")) if re.fullmatch(r"[AC]-\d+", d))
    coverage_map = load(os.path.join(RESULTS, "coverage-map.json"), {})
    rows = [score(r, coverage_map) for r in runs]
    kept = [r for r in rows if not r["discard"]]

    with open(os.path.join(RESULTS, "runs.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    summary = []
    for g in "AC":
        rs = [r for r in kept if r["group"] == g]
        if not rs:
            continue
        summary.append({
            "group": GROUP_NAMES[g],
            "n": len(rs),
            "contract": fmt([r["contract"] for r in rs]),
            "robustness": fmt([r["robustness"] for r in rs]),
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

    print("| 군 | n | 계약 (20) | 견고성 (50) | 테스트 품질 (20) | 완료성 (10) | **총점 (100)** | 비용 ($) | 시간 (분) |")
    print("|---|---|---|---|---|---|---|---|---|")
    for s in summary:
        print(f"| {s['group']} | {s['n']} | {s['contract']} | {s['robustness']} | {s['test_quality']} | "
              f"{s['completeness']} | **{s['total']}** | {s['cost_usd']} | {s['minutes']} |")
    print("\n| run | 총점 | 계약 | 견고성 | 테스트 | 완료성 | 실패 항목 | 테스트 커버 | 상태 | 비용 | 분 | 폐기 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['run']} | {r['total']} | {r['contract']} | {r['robustness']} | {r['test_quality']} | "
              f"{r['completeness']} | {r['failed_items'] or '-'} | {r['items_covered_by_tests']}/{len(RULES)} | "
              f"{r['status']} | {r['cost_usd']} | {r['minutes']} | {r['discard']} |")


if __name__ == "__main__":
    main()
