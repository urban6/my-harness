#!/usr/bin/env bash
# run 하나를 실행한다 (5차 실험, PRD §4).
# 사용법: run.sh <A|B|C> <회차> --task t3|t4 [--name NAME] [--harness-ref REF] [--port PORT] [--budget USD]
#                [--timeout DUR] [--prompt TEXT] [--model ID] [--no-cleanup] [--dry-run]
#   --model: 전 군·C 워커에 같은 모델 (기본 claude-sonnet-5-5, ✋1 이후 claude-haiku-5-5)
#   --prompt: 격리 확인용 짧은 프롬프트 (본 실행은 prompt.txt)
#   - starter(2차 정답 구현 + feature.md) + tasks/<task>/change-request.md를 runs/<이름>/에 복사해 헤드리스 실행
#   - 설정 격리: --setting-sources project,local → 전역 플러그인·에이전트·스킬 제외. 군별 하네스 자산은
#     <harness-ref> 커밋의 사본을 run의 .claude/에 둔다(B: spring-boot 스킬, C: + feature-pm·워커 4종)
#   - --no-cleanup: 라운드 병렬 실행용. Gradle 데몬 정지·컨테이너 정리는 round.sh가 라운드 끝에 한다
set -uo pipefail

PLAYGROUND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$(cd "$PLAYGROUND/../.." && pwd)"
GROUP="${1:?group A|B|C}"; ROUND="${2:?round}"; shift 2
NAME="$GROUP-$ROUND"
TASK=""
HARNESS_REF="HEAD"
APP_PORT=""
PROMPT="$(cat "$PLAYGROUND/prompt.txt")"
BUDGET=40
MODEL=claude-sonnet-5-5
TIMEOUT=180m
CLEANUP=1
DRY_RUN=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --task) TASK="$2"; shift 2 ;;
        --name) NAME="$2"; shift 2 ;;
        --harness-ref) HARNESS_REF="$2"; shift 2 ;;
        --port) APP_PORT="$2"; shift 2 ;;
        --budget) BUDGET="$2"; shift 2 ;;
        --prompt) PROMPT="$2"; shift 2 ;;
        --model) MODEL="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --no-cleanup) CLEANUP=0; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done
[[ "$TASK" == t3 || "$TASK" == t4 ]] || { echo "--task t3|t4 required" >&2; exit 1; }
case "$GROUP" in A) DEFAULT_PORT=8081 ;; B) DEFAULT_PORT=8082 ;; C) DEFAULT_PORT=8083 ;; *) echo "group must be A|B|C" >&2; exit 1 ;; esac
APP_PORT="${APP_PORT:-$DEFAULT_PORT}"

RUN_DIR="$PLAYGROUND/runs/$NAME"
LOG_DIR="$PLAYGROUND/results/logs/$NAME"
[[ -e "$RUN_DIR" ]] && { echo "already exists: $RUN_DIR" >&2; exit 1; }
HARNESS_SHA="$(git -C "$HARNESS" rev-parse "$HARNESS_REF")" || exit 1

COMMON=(-p "$PROMPT" --output-format stream-json --verbose
        --model "$MODEL" --effort high
        --permission-mode bypassPermissions --max-budget-usd "$BUDGET" --strict-mcp-config
        --setting-sources project,local)
case "$GROUP" in
    A) GROUP_FLAGS=(--disable-slash-commands --disallowedTools Agent Workflow) ;;
    B) GROUP_FLAGS=(--disallowedTools Agent Workflow --append-system-prompt "Spring Boot 코드는 spring-boot 스킬을 따른다.") ;;
    C) GROUP_FLAGS=(--agent feature-pm) ;;
esac

if [[ $DRY_RUN -eq 1 ]]; then
    printf 'cd %q && SERVER_PORT=%s timeout %s claude' "$RUN_DIR" "$APP_PORT" "$TIMEOUT"
    printf ' %q' "${COMMON[@]}" "${GROUP_FLAGS[@]}"; echo
    exit 0
fi

# 1) 준비: starter + 변경 요청 복사, 군별 하네스 사본, 읽기 차단, 독립 git 저장소
mkdir -p "$PLAYGROUND/runs" "$LOG_DIR"
rsync -a --exclude .gradle --exclude build "$PLAYGROUND/starter/" "$RUN_DIR/"
cp "$PLAYGROUND/tasks/$TASK/change-request.md" "$RUN_DIR/change-request.md"
mkdir -p "$RUN_DIR/.claude"
case "$GROUP" in
    B) git -C "$HARNESS" archive "$HARNESS_SHA" skills/spring-boot | tar -x -C "$RUN_DIR/.claude" ;;
    C) git -C "$HARNESS" archive "$HARNESS_SHA" skills/spring-boot agents/feature-pm.md agents/backend-designer.md \
           agents/backend-impl.md agents/boundary-verifier.md agents/test-writer.md | tar -x -C "$RUN_DIR/.claude" ;;
esac
rm -rf "$RUN_DIR/.claude/skills/spring-boot/evals"  # 평가 부산물 — 스킬 본문이 아니다
python3 - "$RUN_DIR/.claude/settings.local.json" "$PLAYGROUND" "$HARNESS" "$NAME" <<'PY'
import json, os, sys
path, exp, harness, me = sys.argv[1:5]
pg = os.path.dirname(exp)
deny = [f"Read(/{os.path.join(exp, d)}/**)" for d in ("acceptance", "scripts", "results", "tasks", "starter")]
deny += [f"Read(/{os.path.join(exp, f)})" for f in ("PRD.md", "REPORT.md", "prompt.txt")]
deny += [f"Read(/{os.path.join(pg, d)}/**)" for d in sorted(os.listdir(pg))
         if os.path.isdir(os.path.join(pg, d)) and d != os.path.basename(exp)]
deny += [f"Read(/{os.path.join(pg, f)})" for f in sorted(os.listdir(pg)) if os.path.isfile(os.path.join(pg, f))]
deny += [f"Read(/{os.path.join(harness, d)}/**)" for d in ("agents", "skills", "commands", "rules", "hooks", "prompts", "docs")]
runs = os.path.join(exp, "runs")
deny += [f"Read(/{os.path.join(runs, o)}/**)" for o in sorted(os.listdir(runs)) if o != me]
cfg = {"claudeMdExcludes": [os.path.join(harness, "CLAUDE.md")], "permissions": {"deny": deny}}
json.dump(cfg, open(path, "w"), indent=2)
PY
(cd "$RUN_DIR" && git init -q && git add -A && \
    git -c user.name=harness-eval -c user.email=harness-eval@localhost commit -qm "starter")

# 2) 실행 — 로그는 run 디렉터리 밖에 쓴다(에이전트가 보지 않게)
START=$(date +%s)
echo "[run:$NAME] start $(date '+%F %T') group=$GROUP task=$TASK model=$MODEL harness=${HARNESS_SHA:0:7} port=$APP_PORT budget=\$$BUDGET timeout=$TIMEOUT"
# 헤드리스(-p)는 마지막 턴 뒤 10분이 지나면 백그라운드 워커를 강제 종료한다(4차 C 파일럿에서 발견). 끝까지 기다린다.
GROUP_ENV=(CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0 SERVER_PORT="$APP_PORT")
# 워커 정의의 model: opus·sonnet·haiku 별칭이 모두 같은 모델로 풀리게 한다
[[ "$GROUP" == C ]] && GROUP_ENV+=(ANTHROPIC_DEFAULT_OPUS_MODEL="$MODEL" ANTHROPIC_DEFAULT_SONNET_MODEL="$MODEL"
                                   ANTHROPIC_DEFAULT_HAIKU_MODEL="$MODEL")
(cd "$RUN_DIR" && env "${GROUP_ENV[@]}" timeout "$TIMEOUT" claude "${COMMON[@]}" "${GROUP_FLAGS[@]}" \
    >"$LOG_DIR/session.jsonl" 2>"$LOG_DIR/stderr.log" </dev/null)
EXIT=$?
END=$(date +%s)
echo "[run:$NAME] end exit=$EXIT wall=$((END - START))s"

# 3) 정리: run 디렉터리에서 띄운 java 프로세스. 데몬·컨테이너는 단독 실행일 때만 여기서 정리
for pid in $(pgrep -x java || true); do
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
    if [[ "$cwd" == "$RUN_DIR"* || "$cmd" == *"$RUN_DIR"* ]]; then kill "$pid" 2>/dev/null || true; fi
done
if [[ $CLEANUP -eq 1 ]]; then
    (cd "$RUN_DIR" && ./gradlew --stop -q >/dev/null 2>&1 || true)
fi
if lsof -ti "tcp:$APP_PORT" >/dev/null 2>&1; then echo "[run:$NAME] WARNING: port $APP_PORT still in use" >&2; fi

# 4) 로그 분석 → run-meta.json, 5) 커밋 이력 보존 후 .git 제거
(cd "$RUN_DIR" && git log --stat >"$RUN_DIR/git-log.txt" 2>/dev/null || true)
rm -rf "$RUN_DIR/.git"
cp "$LOG_DIR/session.jsonl" "$RUN_DIR/session.jsonl"
python3 "$PLAYGROUND/scripts/analyze_session.py" "$LOG_DIR/session.jsonl" \
    --run-dir "$RUN_DIR" --group "$GROUP" --task "$TASK" --harness-sha "$HARNESS_SHA" --model "$MODEL" \
    --exit "$EXIT" --wall "$((END - START))" --out "$RUN_DIR/run-meta.json"
