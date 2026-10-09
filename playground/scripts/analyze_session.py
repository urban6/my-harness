#!/usr/bin/env python3
"""stream-json 세션 로그에서 run 메타데이터를 뽑는다 (PRD §4.3 보조 기록, 격리·노출 검사).

사용법: analyze_session.py <session.jsonl> --run-dir DIR --group A|B|C --exit CODE --wall SEC --out run-meta.json
"""
import argparse
import json
import os
import re
from collections import Counter

AGENT_TOOLS = ("Agent", "Task")  # 서브에이전트 도구 이름은 세션 종류에 따라 Agent 또는 Task로 노출된다
PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXPECT = {
    # 군별 격리 기대값: (Agent 도구 허용, Workflow 도구 허용, 스킬 로드)
    "A": {"agent_tool": False, "workflow_tool": False, "skills": False},
    "B": {"agent_tool": False, "workflow_tool": False, "skills": True},
    "C": {"agent_tool": True, "workflow_tool": None, "skills": None},
}


def forbidden_patterns(run_dir):
    me = os.path.basename(run_dir.rstrip("/"))
    return [
        (re.compile(re.escape(os.path.join(PLAYGROUND, d))), d)
        for d in ("acceptance", "scripts", "results", "PRD.md")
    ] + [
        (re.compile(re.escape(os.path.join(PLAYGROUND, "runs")) + r"/(?!" + re.escape(me) + r"(?![\w.-]))[\w.-]+"), "other-run"),
        (re.compile(r"my_harness/CLAUDE\.md"), "harness-CLAUDE.md"),
        (re.compile(r"(^|[\s\"'=])\.\./\.\./"), "escape-../../"),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--group", required=True)
    ap.add_argument("--exit", type=int, required=True)
    ap.add_argument("--wall", type=int, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    init, result = None, None
    tool_counts, skill_calls, agent_calls = Counter(), Counter(), Counter()
    compactions, subagent_events = 0, 0
    leaks = []
    patterns = forbidden_patterns(os.path.abspath(a.run_dir))

    for line in open(a.session, encoding="utf-8"):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        t, st = e.get("type"), e.get("subtype")
        if t == "system" and st == "init" and init is None:
            init = e
        elif t == "system" and st == "compact_boundary":
            compactions += 1
        elif t == "result":
            result = e
        if e.get("parent_tool_use_id"):
            subagent_events += 1
        msg = e.get("message")
        if t == "assistant" and isinstance(msg, dict) and isinstance(msg.get("content"), list):
            for c in msg["content"]:
                if c.get("type") != "tool_use":
                    continue
                name, inp = c.get("name"), c.get("input") or {}
                tool_counts[name] += 1
                if name == "Skill":
                    skill_calls[str(inp.get("skill") or inp.get("command") or inp)] += 1
                if name in AGENT_TOOLS:
                    agent_calls[str(inp.get("subagent_type") or inp.get("name") or "?")] += 1
                blob = json.dumps(inp, ensure_ascii=False)
                if name == "Read" and "skills/spring-boot" in blob:
                    skill_calls["spring-boot (Read)"] += 1
                for pat, label in patterns:
                    if pat.search(blob):
                        leaks.append({"tool": name, "match": label, "input": blob[:300]})

    tools = (init or {}).get("tools", [])
    skills = (init or {}).get("skills", [])
    exp = EXPECT[a.group]
    isolation = {
        "agent_tool_present": any(t in tools for t in AGENT_TOOLS),
        "workflow_tool_present": "Workflow" in tools,
        "skills_loaded": bool(skills),
        "spring_boot_skill_available": any("spring-boot" in s for s in skills),
    }
    problems = []
    if exp["agent_tool"] is not None and isolation["agent_tool_present"] != exp["agent_tool"]:
        problems.append("agent_tool")
    if exp["workflow_tool"] is not None and isolation["workflow_tool_present"] != exp["workflow_tool"]:
        problems.append("workflow_tool")
    if exp["skills"] is not None and isolation["skills_loaded"] != exp["skills"]:
        problems.append("skills")
    if a.group == "B" and not isolation["spring_boot_skill_available"]:
        problems.append("spring_boot_skill_missing")
    isolation["ok"] = init is not None and not problems
    isolation["problems"] = problems

    r = result or {}
    if a.exit == 124:
        status = "timeout"
    elif r.get("subtype") == "success" and not r.get("is_error"):
        status = "success"
    elif r:
        status = r.get("subtype") or "error"
    else:
        status = f"no-result(exit={a.exit})"

    meta = {
        "run": os.path.basename(os.path.abspath(a.run_dir)),
        "group": a.group,
        "status": status,
        "exit_code": a.exit,
        "wall_seconds": a.wall,
        "cost_usd": r.get("total_cost_usd"),
        "duration_ms": r.get("duration_ms"),
        "num_turns": r.get("num_turns"),
        "usage": r.get("usage"),
        "model_usage": r.get("modelUsage"),
        "subagent_stats": r.get("subagent_stats"),
        "model": (init or {}).get("model"),
        "isolation": isolation,
        "compactions": compactions,
        "subagent_events_in_log": subagent_events,
        "tool_counts": dict(tool_counts.most_common()),
        "skill_calls": dict(skill_calls),
        "agent_calls": dict(agent_calls),
        "forbidden_access": leaks,
        "discard": bool(leaks) or not isolation["ok"],
    }
    json.dump(meta, open(a.out, "w"), ensure_ascii=False, indent=2)
    print(f"[meta:{meta['run']}] status={status} cost=${meta['cost_usd']} turns={meta['num_turns']} "
          f"isolation_ok={isolation['ok']} leaks={len(leaks)} compactions={compactions} discard={meta['discard']}")


if __name__ == "__main__":
    main()
