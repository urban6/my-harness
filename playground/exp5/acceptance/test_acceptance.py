#!/usr/bin/env python3
"""5차 실험 숨긴 인수 테스트 실행기 (PRD §5).

- 케이스 = 과제별 새 케이스(t3_cases / t4_cases) + 2차 회귀 케이스(regression, 충돌하는 것만 제외).
- 층: spec(명세 내, 주 지표) / interp(명세 해석, 별도 보고). 회귀 케이스는 전부 spec이고 요구사항 "REG" 하나로 묶는다.
- 주 지표 requirement_mean = 과제 요구사항(P1~P6 또는 M1~M8)과 REG의 spec 통과율 평균.
- 단계: main(시간 의존 제외, 앱 TTL 길게) / ttl(시간 의존만, 앱 TTL=PT3S로 다시 기동).
- T4는 회귀 케이스에 고정 테넌트 헤더 하나를 주입한다.

사용법: test_acceptance.py --task t3|t4 --base-url URL --pg-url URL [--phase main|ttl|all] [--ttl-seconds 600]
                          [--out result.json] [--only REQ,...] [--skip case,...]
"""
import argparse
import importlib
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regression as rg  # noqa: E402


def load_task(task):
    return importlib.import_module(f"{task}_cases")


def build_cases(task):
    """[{req, fn, layer, ttl, reg_req}]"""
    mod = load_task(task)
    cases = [{"req": "REG", "fn": f, "layer": "spec", "ttl": r == "R6", "reg_req": r}
             for r, f in rg.CASES if f.__name__ not in mod.REGRESSION_EXCLUDE]
    cases += [{"req": r, "fn": f, "layer": layer, "ttl": ttl, "reg_req": None} for r, f, layer, ttl in mod.CASES]
    return cases


def all_cases_meta(task):
    """채점 스크립트가 기동 실패 단계의 케이스를 실패로 채울 때 쓴다."""
    return [{"id": c["fn"].__name__, "req": c["req"], "layer": c["layer"], "ttl": c["ttl"], "reg_req": c["reg_req"]}
            for c in build_cases(task)]


def summarize(task, results):
    mod = load_task(task)
    reqs = mod.REQUIREMENTS + ["REG"]
    spec = [r for r in results if r["layer"] == "spec"]
    interp = [r for r in results if r["layer"] == "interp"]
    by_req = {}
    for r in spec:
        by_req.setdefault(r["req"], []).append(r["passed"])
    rates = {k: round(sum(v) / len(v), 4) for k, v in by_req.items()}
    reg = {}
    for r in spec:
        if r["req"] == "REG":
            reg.setdefault(r["reg_req"], []).append(r["passed"])
    categories = {}
    for r in results:
        if not r["passed"]:
            categories[r.get("category", "?")] = categories.get(r.get("category", "?"), 0) + 1
    return {
        "task": task,
        "requirement_mean": round(sum(rates.get(r, 0.0) for r in reqs) / len(reqs), 4),
        "requirement_rates": {r: rates.get(r) for r in reqs},
        "spec_passed": sum(r["passed"] for r in spec),
        "spec_total": len(spec),
        "new_passed": sum(r["passed"] for r in spec if r["req"] != "REG"),
        "new_total": sum(1 for r in spec if r["req"] != "REG"),
        "regression_passed": sum(r["passed"] for r in spec if r["req"] == "REG"),
        "regression_total": sum(1 for r in spec if r["req"] == "REG"),
        "regression_rates": {k: round(sum(v) / len(v), 4) for k, v in sorted(reg.items(), key=lambda kv: int(kv[0][1:]))},
        "interp_passed": sum(r["passed"] for r in interp),
        "interp_total": len(interp),
        "failed": [r["id"] for r in results if not r["passed"]],
        "failure_categories": categories,
        "regression_excluded": load_task(task).REGRESSION_EXCLUDE,
        "cases": results,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", choices=["t3", "t4"], required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--pg-url", required=True)
    ap.add_argument("--phase", choices=["main", "ttl", "all"], default="main")
    ap.add_argument("--ttl-seconds", type=float, default=600)
    ap.add_argument("--out")
    ap.add_argument("--only", help="실행할 요구사항 ID(P1·M2·REG·I 등), 쉼표 구분")
    ap.add_argument("--skip", help="제외할 케이스 함수명, 쉼표 구분")
    args = ap.parse_args()
    rg.BASE, rg.PG, rg.TTL_SECONDS = args.base_url.rstrip("/"), args.pg_url.rstrip("/"), args.ttl_seconds
    if args.task == "t4":
        rg.DEFAULT_HEADERS["X-Tenant-Id"] = "reg-" + uuid.uuid4().hex[:8]

    only = set(args.only.split(",")) if args.only else None
    skip = set(args.skip.split(",")) if args.skip else set()
    in_phase = {"main": lambda c: not c["ttl"], "ttl": lambda c: c["ttl"], "all": lambda c: True}[args.phase]
    cases = [c for c in build_cases(args.task)
             if in_phase(c) and (only is None or c["req"] in only) and c["fn"].__name__ not in skip]

    results = []
    for c in cases:
        fn = c["fn"]
        started = time.time()
        entry = {"id": fn.__name__, "req": c["req"], "layer": c["layer"], "reg_req": c["reg_req"],
                 "rules": (fn.__doc__ or "").split()}
        try:
            fn()
            entry["passed"] = True
        except rg.Fail as f:
            entry.update(passed=False, category=f.category, message=str(f))
        except Exception as e:  # 연결 실패·타임아웃 등
            entry.update(passed=False, category="error", message=f"{type(e).__name__}: {e}")
        entry["seconds"] = round(time.time() - started, 2)
        results.append(entry)
        mark = "PASS" if entry["passed"] else f"FAIL[{entry['category']}]"
        label = c["req"] if c["req"] != "REG" else f"REG/{c['reg_req']}"
        print(f"{mark:22} {label:8} {fn.__name__}" + ("" if entry["passed"] else f" — {entry['message'][:200]}"),
              flush=True)

    summary = summarize(args.task, results)
    summary["phase"] = args.phase
    print(f"\nspec {summary['spec_passed']}/{summary['spec_total']} "
          f"(new {summary['new_passed']}/{summary['new_total']}, regression "
          f"{summary['regression_passed']}/{summary['regression_total']}), interp "
          f"{summary['interp_passed']}/{summary['interp_total']}, requirement_mean={summary['requirement_mean']} "
          f"rates={summary['requirement_rates']}")
    if args.out:
        with open(args.out, "w") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
