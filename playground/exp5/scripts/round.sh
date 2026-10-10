#!/usr/bin/env bash
# 라운드 하나: 세 run을 동시에 실행하고(run.sh --no-cleanup), 끝나면 정리한 뒤 순차 채점한다 (PRD §4).
#   채점은 부하가 점수에 섞이지 않게 실행이 모두 끝난 뒤 한 번에 하나씩 한다.
# 사용법: round.sh <t3|t4> <회차> [--harness-ref REF] [--model ID] [--sequential]
#   --sequential: 병렬 간섭이 확인되면 쓴다. 회차에 따라 군 순서를 바꿔 하나씩 실행(PRD §4)
set -uo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXP="$(cd "$SCRIPTS/.." && pwd)"
TASK="${1:?task t3|t4}"; N="${2:?round number}"; shift 2
REF="HEAD"; SEQ=0; MODEL=claude-sonnet-5-5
while [[ $# -gt 0 ]]; do
    case "$1" in
        --harness-ref) REF="$2"; shift 2 ;;
        --model) MODEL="$2"; shift 2 ;;
        --sequential) SEQ=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done
REF="$(git -C "$EXP/../.." rev-parse "$REF")"
mkdir -p "$EXP/results/logs"
docker ps -aq | sort >"$EXP/results/logs/round-$N.containers.before"

if [[ $SEQ -eq 1 ]]; then
    case $(( (N - 1) % 3 )) in 0) ORDER=(A B C) ;; 1) ORDER=(B C A) ;; 2) ORDER=(C A B) ;; esac
    for g in "${ORDER[@]}"; do "$SCRIPTS/run.sh" "$g" "$N" --task "$TASK" --harness-ref "$REF" --model "$MODEL"; done
else
    pids=()
    for g in A B C; do
        "$SCRIPTS/run.sh" "$g" "$N" --task "$TASK" --harness-ref "$REF" --model "$MODEL" --no-cleanup \
            >"$EXP/results/logs/run-$g-$N.out" 2>&1 &
        pids+=($!)
    done
    for p in "${pids[@]}"; do wait "$p"; done
    cat "$EXP/results/logs/run-"{A,B,C}"-$N.out"
    # 라운드 정리: Gradle 데몬 정지, 라운드 중 새로 생긴 컨테이너 제거
    (cd "$EXP/runs/A-$N" && ./gradlew --stop -q >/dev/null 2>&1 || true)
    docker ps -aq | sort >"$EXP/results/logs/round-$N.containers.after"
    comm -13 "$EXP/results/logs/round-$N.containers.before" "$EXP/results/logs/round-$N.containers.after" \
        | xargs -r docker rm -f >/dev/null 2>&1 || true
fi

for g in A B C; do "$SCRIPTS/grade.sh" "$EXP/runs/$g-$N" "$TASK"; done
echo "[round $N] done task=$TASK harness=${REF:0:7}"
