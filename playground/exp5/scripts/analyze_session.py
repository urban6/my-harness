#!/usr/bin/env python3
"""stream-json 세션 로그에서 run 메타데이터를 뽑는다 (PRD §4.3 보조 기록, 격리·노출 검사).

사용법: analyze_session.py <session.jsonl> --run-dir DIR --group A|B|C --task t3|t4 --harness-sha SHA
                           --exit CODE --wall SEC --out run-meta.json
"""
import argparse
import json
import os
import re
from collections import Counter

AGENT_TOOLS = ("Agent", "Task")  # 서브에이전트 도구 이름은 세션 종류에 따라 Agent 또는 Task로 노출된다
PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # exp5/
EXP1 = os.path.dirname(PLAYGROUND)  # playground/ — 이전 실험 자산 전체
HARNESS = os.path.dirname(EXP1)     # 저장소 루트 — live 하네스 자산 접근도 노출로 본다(사본만 써야 한다)
OTHER_EXPS = ("exp2", "exp3", "exp4")  # exp2에는 회귀 테스트 원본이 있다 — 디렉터리 전체 금지
MODEL_FAMILIES = ("opus", "sonnet", "haiku")  # 메인·워커 모두 --model 하나. 다른 계열이 섞이면 폐기
BUILTIN_PLUGIN_PREFIX = "cc-plugin-"  # 앱 내장 플러그인. 그 밖의 플러그인(전역 설치)이 보이면 격리 실패
HARNESS_AGENTS = {"feature-pm", "backend-designer", "backend-impl", "boundary-verifier", "test-writer"}
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
        for d in ("acceptance", "scripts", "results", "tasks", "starter", "PRD.md", "REPORT.md", "prompt.txt")
    ] + [
        (re.compile(re.escape(os.path.join(PLAYGROUND, "runs")) + r"/(?!" + re.escape(me) + r"(?![\w-]|\.[\w-]))[\w.-]+"), "other-run"),  # 자기 경로 뒤 문장 마침표는 허용
        (re.compile(r"my_harness/CLAUDE\.md"), "harness-CLAUDE.md"),
    ] + [
        (re.compile(re.escape(os.path.join(HARNESS, d)) + "/"), f"live-{d}")
        for d in ("agents", "skills", "commands", "rules", "hooks", "prompts", "docs")
    ] + [
        (re.compile(re.escape(os.path.expanduser(os.path.join("~/.claude", d))) + "/"), f"global-{d}")
        for d in ("agents", "skills", "plugins")
    ] + [
        (re.compile(re.escape(os.path.join(EXP1, d))), f"exp1-{d}")
        for d in ("acceptance", "scripts", "results", "runs", "pilot", "PRD.md", "REPORT.md", "feature.md")
    ] + [
        (re.compile(re.escape(os.path.join(EXP1, d))), d)
        for d in OTHER_EXPS
    ]


# 휴리스틱: 상위로 두 단계 이상 이동. 하위 폴더에서 run 루트로 돌아오는 정상 사용도 걸리므로
# 자동 폐기하지 않고 경고로만 남겨 사람이 확인한다.
ESCAPE_HINT = re.compile(r"(^|[\s\"'=])\.\./\.\./")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--group", required=True)
    ap.add_argument("--task", required=True)
    ap.add_argument("--harness-sha", required=True)
    ap.add_argument("--model", default="claude-sonnet-5-5")
    ap.add_argument("--exit", type=int, required=True)
    ap.add_argument("--wall", type=int, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    init, result = None, None
    tool_counts, skill_calls, agent_calls = Counter(), Counter(), Counter()
    compactions, subagent_events = 0, 0
    leaks, warnings = [], []
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
                if ESCAPE_HINT.search(blob):
                    warnings.append({"tool": name, "match": "escape-../../", "input": blob[:300]})

    tools = (init or {}).get("tools", [])
    skills = (init or {}).get("skills", [])
    plugins = [p.get("name") for p in (init or {}).get("plugins", [])]
    agents = set((init or {}).get("agents", []))
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
    foreign_plugins = [p for p in plugins if not str(p).startswith(BUILTIN_PLUGIN_PREFIX)]
    if foreign_plugins:
        problems.append(f"plugins:{foreign_plugins}")
    has_harness_agents = HARNESS_AGENTS <= agents
    if a.group == "C" and not has_harness_agents:
        problems.append(f"harness_agents_missing:{sorted(HARNESS_AGENTS - agents)}")
    if a.group in ("A", "B") and agents & HARNESS_AGENTS:
        problems.append(f"harness_agents_present:{sorted(agents & HARNESS_AGENTS)}")
    if a.group == "A" and any("spring-boot" in s for s in skills):
        problems.append("spring_boot_skill_present")
    if a.group == "C" and not isolation["spring_boot_skill_available"]:
        problems.append("spring_boot_skill_missing")
    isolation.update(plugins=plugins, agents=sorted(agents), skills=skills)
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
        "task": a.task,
        "harness_sha": a.harness_sha,
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
        "warnings": warnings,
        "models_used": sorted((r.get("modelUsage") or {}).keys()),
        "expected_model": a.model,
        "other_models": sorted(m for m in (r.get("modelUsage") or {}) if m != a.model),
        "discard": bool(leaks) or not isolation["ok"],
    }
    # 지정 모델과 다른 계열(opus·sonnet·haiku)이 섞이면 실험 조건 위반 → 폐기. 같은 계열의 다른 id는 경고만.
    family = next((f for f in MODEL_FAMILIES if f in a.model), None)
    if any(f in m for m in meta["other_models"] for f in MODEL_FAMILIES if f != family):
        meta["discard"] = True
    elif meta["other_models"]:
        warnings.append({"tool": "-", "match": "other-model", "input": ", ".join(meta["other_models"])})
    json.dump(meta, open(a.out, "w"), ensure_ascii=False, indent=2)
    print(f"[meta:{meta['run']}] status={status} cost=${meta['cost_usd']} turns={meta['num_turns']} "
          f"isolation_ok={isolation['ok']} leaks={len(leaks)} warnings={len(warnings)} compactions={compactions} models={meta['models_used']} discard={meta['discard']}")


if __name__ == "__main__":
    main()
