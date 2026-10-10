#!/usr/bin/env bash
# run 하나를 실행한다 (3차 실험 — 짧은 명세, Sonnet, PRD §8).
# 사용법: run.sh <A|B|C> <회차> [--name NAME] [--effort LEVEL] [--feature FILE] [--prompt TEXT] [--budget USD] [--timeout DUR] [--dry-run]
#   기본: runs/<군>-<회차>/ 에 starter + feature-short.md를 복사하고 prompt.txt로 헤드리스 실행
set -uo pipefail

PLAYGROUND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GROUP="${1:?group A|B|C}"; ROUND="${2:?round}"; shift 2
NAME="$GROUP-$ROUND"
FEATURE="$PLAYGROUND/feature-short.md"
PROMPT="$(cat "$PLAYGROUND/prompt.txt")"
BUDGET=15
TIMEOUT=180m
EFFORT=high
DRY_RUN=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --feature) FEATURE="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2 ;;
        --prompt) PROMPT="$2"; shift 2 ;;
        --budget) BUDGET="$2"; shift 2 ;;
        --effort) EFFORT="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

RUN_DIR="$PLAYGROUND/runs/$NAME"
LOG_DIR="$PLAYGROUND/results/logs/$NAME"
[[ -e "$RUN_DIR" ]] && { echo "already exists: $RUN_DIR" >&2; exit 1; }

COMMON=(-p "$PROMPT" --output-format stream-json --verbose
        --model claude-sonnet-5-5 --effort "$EFFORT"
        --permission-mode bypassPermissions --max-budget-usd "$BUDGET" --strict-mcp-config)
case "$GROUP" in
    A) GROUP_FLAGS=(--disable-slash-commands --disallowedTools Agent Workflow) ;;
    B) GROUP_FLAGS=(--disallowedTools Agent Workflow --append-system-prompt "Spring Boot 코드는 spring-boot 스킬을 따른다.") ;;  # 2차와 같은 지시 (보충 실험)
    C) GROUP_FLAGS=(--agent feature-pm) ;;
    *) echo "group must be A|B|C" >&2; exit 1 ;;
esac

if [[ $DRY_RUN -eq 1 ]]; then
    printf 'cd %q && timeout %s claude' "$RUN_DIR" "$TIMEOUT"; printf ' %q' "${COMMON[@]}" "${GROUP_FLAGS[@]}"; echo
    exit 0
fi

# 1) 준비: starter + feature-short.md 복사, 다른 run 읽기 차단 추가, 독립 git 저장소
mkdir -p "$PLAYGROUND/runs" "$LOG_DIR"
rsync -a --exclude .gradle --exclude build "$PLAYGROUND/starter/" "$RUN_DIR/"
cp "$FEATURE" "$RUN_DIR/feature-short.md"
python3 - "$RUN_DIR/.claude/settings.local.json" "$PLAYGROUND/runs" "$NAME" <<'PY'
import json, os, sys
path, runs, me = sys.argv[1:4]
cfg = json.load(open(path))
deny = cfg.setdefault("permissions", {}).setdefault("deny", [])
for other in sorted(os.listdir(runs)):
    if other != me and os.path.isdir(os.path.join(runs, other)):
        deny.append(f"Read(/{os.path.join(runs, other)}/**)")
json.dump(cfg, open(path, "w"), indent=2)
PY
(cd "$RUN_DIR" && git init -q && git add -A && \
    git -c user.name=harness-eval -c user.email=harness-eval@localhost commit -qm "starter")

# 2) 실행 — 로그는 run 디렉터리 밖에 쓴다(에이전트가 보지 않게)
docker ps -aq | sort >"$LOG_DIR/containers.before"
START=$(date +%s)
echo "[run:$NAME] start $(date '+%F %T') group=$GROUP effort=$EFFORT budget=\$$BUDGET timeout=$TIMEOUT"
# 헤드리스(-p)는 마지막 턴 뒤 10분이 지나면 백그라운드 워커를 강제 종료한다(4차 C 파일럿에서 발견).
# 대화형과 같게 끝까지 기다린다. 전체 상한은 timeout이 맡는다.
GROUP_ENV=(CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0)
[[ "$GROUP" == C ]] && GROUP_ENV+=(ANTHROPIC_DEFAULT_OPUS_MODEL=claude-sonnet-5-5)  # 워커 정의의 model: opus도 Sonnet으로
(cd "$RUN_DIR" && env ${GROUP_ENV[@]+"${GROUP_ENV[@]}"} timeout "$TIMEOUT" claude "${COMMON[@]}" "${GROUP_FLAGS[@]}" \
    >"$LOG_DIR/session.jsonl" 2>"$LOG_DIR/stderr.log" </dev/null)
EXIT=$?
END=$(date +%s)
echo "[run:$NAME] end exit=$EXIT wall=$((END - START))s"

# 3) 정리: run 디렉터리에서 띄운 java 프로세스, Gradle 데몬, 새로 생긴 컨테이너
for pid in $(pgrep -x java || true); do
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
    if [[ "$cwd" == "$RUN_DIR"* || "$cmd" == *"$RUN_DIR"* ]]; then kill "$pid" 2>/dev/null || true; fi
done
(cd "$RUN_DIR" && ./gradlew --stop -q >/dev/null 2>&1 || true)
docker ps -aq | sort >"$LOG_DIR/containers.after"
comm -13 "$LOG_DIR/containers.before" "$LOG_DIR/containers.after" | xargs -r docker rm -f >/dev/null 2>&1 || true
if lsof -ti tcp:8080 >/dev/null 2>&1; then echo "[run:$NAME] WARNING: port 8080 still in use" >&2; fi

# 4) 로그 분석 → run-meta.json, 5) 커밋 이력 보존 후 .git 제거
(cd "$RUN_DIR" && git log --stat >"$RUN_DIR/git-log.txt" 2>/dev/null || true)
rm -rf "$RUN_DIR/.git"
cp "$LOG_DIR/session.jsonl" "$RUN_DIR/session.jsonl"
python3 "$PLAYGROUND/scripts/analyze_session.py" "$LOG_DIR/session.jsonl" \
    --run-dir "$RUN_DIR" --group "$GROUP" --exit "$EXIT" --wall "$((END - START))" --out "$RUN_DIR/run-meta.json"
