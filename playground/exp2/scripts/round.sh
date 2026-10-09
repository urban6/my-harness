#!/usr/bin/env bash
# 라운드 하나를 순차 실행한다: 각 run을 실행(run.sh)한 직후 채점(grade.sh)한다.
# 사용법: round.sh B-1 C-1 A-1
set -uo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for spec in "$@"; do
    group="${spec%%-*}"; n="${spec#*-}"
    "$SCRIPTS/run.sh" "$group" "$n"
    "$SCRIPTS/grade.sh" "$SCRIPTS/../runs/$spec"
done
echo "[round] done: $*"
