#!/usr/bin/env bash
# run 디렉터리 하나를 채점한다 (PRD §4.1).
#   assemble → test(+JaCoCo, 실패 시 1회 재실행) → 새 PostgreSQL + 앱 기동 → 인수 테스트
# 사용법: grade.sh <run_dir> [out_json]
#   파일럿 등 범위 축소: ACCEPTANCE_ARGS="--only R1,R2,R8 --skip r8_conflict_problem" grade.sh ...
set -uo pipefail

PLAYGROUND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$(cd "$1" && pwd)"
NAME="$(basename "$RUN_DIR")"
OUT="${2:-$PLAYGROUND/results/$NAME.json}"
[[ "$OUT" = /* ]] || OUT="$PWD/$OUT"
LOG_DIR="$PLAYGROUND/results/logs/$NAME"
INIT="$PLAYGROUND/scripts/grading.init.gradle"
mkdir -p "$LOG_DIR" "$(dirname "$OUT")"

PG_IMAGE="postgres:16-alpine"
APP_PORT="${APP_PORT:-18080}"
PG_PORT="${PG_PORT:-15432}"
PG_NAME="grade-pg-$NAME-$$"

log() { echo "[grade:$NAME] $*"; }
cd "$RUN_DIR"
chmod +x ./gradlew 2>/dev/null || true

# 1) 앱 빌드 (테스트 제외)
assemble_ok=false
if timeout 15m ./gradlew assemble -q >"$LOG_DIR/assemble.log" 2>&1; then assemble_ok=true; fi
log "assemble_ok=$assemble_ok"

# 2) 자체 테스트 + JaCoCo. 결과 판정은 JUnit XML로 한다(ignoreFailures 때문에 종료 코드로는 판정 불가)
run_tests() {
    rm -rf build/test-results/test build/reports/jacoco
    timeout 30m ./gradlew test --init-script "$INIT" --continue >"$LOG_DIR/test-$1.log" 2>&1
    echo $? >"$LOG_DIR/test-$1.exit"
}
summarize_tests() {
    python3 - "$RUN_DIR" "$LOG_DIR/test-$1.exit" <<'PY'
import glob, json, sys, xml.etree.ElementTree as ET
run, exit_file = sys.argv[1], sys.argv[2]
t = f = e = s = 0
for p in glob.glob(f"{run}/**/build/test-results/test/*.xml", recursive=True):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
code = int(open(exit_file).read().strip() or 1)
print(json.dumps({"tests": t, "failures": f, "errors": e, "skipped": s, "gradle_exit": code,
                  "ok": code == 0 and t > 0 and f == 0 and e == 0}))
PY
}
test_rerun=false
run_tests 1; tests_json="$(summarize_tests 1)"
if [[ "$(echo "$tests_json" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')" != "True" ]]; then
    test_rerun=true
    log "self tests failed, rerunning once"
    run_tests 2; tests_json="$(summarize_tests 2)"
fi
log "tests=$tests_json"

coverage_json="$(python3 - "$RUN_DIR" <<'PY'
import glob, json, sys, xml.etree.ElementTree as ET
covered = missed = 0
for p in glob.glob(f"{sys.argv[1]}/**/build/reports/jacoco/test/jacocoTestReport.xml", recursive=True):
    root = ET.parse(p).getroot()
    for c in root.findall("counter"):
        if c.get("type") == "BRANCH":
            covered += int(c.get("covered")); missed += int(c.get("missed"))
total = covered + missed
print(json.dumps({"branch_covered": covered, "branch_total": total,
                  "branch_coverage": round(covered / total, 4) if total else 0.0}))
PY
)"
log "coverage=$coverage_json"

# 3) 새 PostgreSQL 컨테이너 + 앱 기동 (feature.md 런타임 계약: 환경 변수로 덮어쓰기)
rm -f "$LOG_DIR/acceptance.json"
boot_ok=false
cleanup() {
    [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null
    docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

JAR="$(ls build/libs/*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -1 || true)"
if $assemble_ok && [[ -n "$JAR" ]]; then
    docker run -d --rm --name "$PG_NAME" -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app \
        -p "$PG_PORT:5432" "$PG_IMAGE" >/dev/null
    for _ in $(seq 1 60); do docker exec "$PG_NAME" pg_isready -U app -d app >/dev/null 2>&1 && break; sleep 1; done
    sleep 1
    SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/app" \
    SPRING_DATASOURCE_USERNAME=app SPRING_DATASOURCE_PASSWORD=app SERVER_PORT="$APP_PORT" \
        java -jar "$JAR" >"$LOG_DIR/app.log" 2>&1 &
    APP_PID=$!
    for _ in $(seq 1 120); do
        if curl -s -o /dev/null "http://localhost:$APP_PORT/"; then boot_ok=true; break; fi
        kill -0 "$APP_PID" 2>/dev/null || break
        sleep 1
    done
fi
log "boot_ok=$boot_ok"

# 4) 인수 테스트
if $boot_ok; then
    python3 "$PLAYGROUND/acceptance/test_acceptance.py" --base-url "http://localhost:$APP_PORT" ${ACCEPTANCE_ARGS:-} \
        --out "$LOG_DIR/acceptance.json" >"$LOG_DIR/acceptance.txt" 2>&1
    tail -1 "$LOG_DIR/acceptance.txt"
fi

echo "$tests_json" >"$LOG_DIR/tests.json"
echo "$coverage_json" >"$LOG_DIR/coverage.json"
python3 - "$OUT" "$NAME" "$assemble_ok" "$test_rerun" "$boot_ok" "$LOG_DIR" <<'PY'
import json, os, sys
out, name, assemble_ok, rerun, boot_ok, log_dir = sys.argv[1:7]
acc_path = os.path.join(log_dir, "acceptance.json")
result = {
    "run": name,
    "assemble_ok": assemble_ok == "true",
    "tests": json.load(open(os.path.join(log_dir, "tests.json"))),
    "test_rerun": rerun == "true",
    "coverage": json.load(open(os.path.join(log_dir, "coverage.json"))),
    "boot_ok": boot_ok == "true",
    "acceptance": json.load(open(acc_path)) if boot_ok == "true" and os.path.exists(acc_path) else None,
}
json.dump(result, open(out, "w"), ensure_ascii=False, indent=2)
PY
log "written $OUT"
