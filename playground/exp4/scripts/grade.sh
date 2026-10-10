#!/usr/bin/env bash
# run 디렉터리 하나를 채점한다 (2차 실험, PRD §5).
#   assemble → test(+JaCoCo, 실패 시 1회 재실행) → 새 PostgreSQL + PG 스텁 + 앱 기동
#   → 인수 테스트 main 단계(TTL=PT10M) → 앱 재기동(TTL=PT3S) → ttl 단계(R6)
# 사용법: grade.sh <run_dir> [out_json]
#   범위 축소: ACCEPTANCE_ARGS="--only R1,R2" grade.sh ...
set -uo pipefail

PLAYGROUND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$(cd "$1" && pwd)"
NAME="$(basename "$RUN_DIR")"
OUT="${2:-$PLAYGROUND/results/$NAME.json}"
[[ "$OUT" = /* ]] || OUT="$PWD/$OUT"
LOG_DIR="$PLAYGROUND/results/logs/$NAME"
INIT="$PLAYGROUND/scripts/grading.init.gradle"
ACCEPTANCE="$PLAYGROUND/acceptance"
mkdir -p "$LOG_DIR" "$(dirname "$OUT")"

PG_IMAGE="postgres:16-alpine"
APP_PORT="${APP_PORT:-18080}"
DB_PORT="${DB_PORT:-15432}"
STUB_PORT="${STUB_PORT:-18090}"
DB_NAME="grade-pg-$NAME-$$"
MAIN_TTL="PT10M"; MAIN_TTL_SECONDS=600
SHORT_TTL="PT3S"; SHORT_TTL_SECONDS=3

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

# 3) 새 PostgreSQL 컨테이너 + PG 스텁 + 앱 (feature.md C4: 환경 변수로 덮어쓰기)
rm -f "$LOG_DIR"/acceptance-*.json
APP_PID=""; STUB_PID=""
stop_app() {
    if [[ -n "$APP_PID" ]]; then kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null; APP_PID=""; fi
}
cleanup() {
    stop_app
    [[ -n "$STUB_PID" ]] && { kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; }
    docker rm -f "$DB_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

JAR="$(ls build/libs/*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -1 || true)"
start_app() {  # $1=TTL, $2=log suffix → boot_ok 여부 반환
    SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$DB_PORT/app" \
    SPRING_DATASOURCE_USERNAME=app SPRING_DATASOURCE_PASSWORD=app SERVER_PORT="$APP_PORT" \
    PAYMENT_GATEWAY_URL="http://localhost:$STUB_PORT" ORDER_PAYMENT_TTL="$1" \
        java -jar "$JAR" >"$LOG_DIR/app-$2.log" 2>&1 &
    APP_PID=$!
    for _ in $(seq 1 120); do
        if curl -s -o /dev/null "http://localhost:$APP_PORT/"; then return 0; fi
        kill -0 "$APP_PID" 2>/dev/null || return 1
        sleep 1
    done
    return 1
}
run_acceptance() {  # $1=phase, $2=ttl seconds
    python3 "$ACCEPTANCE/test_acceptance.py" --base-url "http://localhost:$APP_PORT" \
        --pg-url "http://localhost:$STUB_PORT" --phase "$1" --ttl-seconds "$2" ${ACCEPTANCE_ARGS:-} \
        --out "$LOG_DIR/acceptance-$1.json" >"$LOG_DIR/acceptance-$1.txt" 2>&1
    tail -1 "$LOG_DIR/acceptance-$1.txt"
}

boot_ok=false; boot_ttl_ok=false
if $assemble_ok && [[ -n "$JAR" ]]; then
    docker run -d --rm --name "$DB_NAME" -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app \
        -p "$DB_PORT:5432" "$PG_IMAGE" >/dev/null
    for _ in $(seq 1 60); do docker exec "$DB_NAME" pg_isready -U app -d app >/dev/null 2>&1 && break; sleep 1; done
    sleep 1
    python3 "$ACCEPTANCE/pg_stub.py" --port "$STUB_PORT" >"$LOG_DIR/pg-stub.log" 2>&1 &
    STUB_PID=$!
    sleep 1

    if start_app "$MAIN_TTL" main; then boot_ok=true; fi
    log "boot_ok=$boot_ok"
    if $boot_ok; then
        run_acceptance main "$MAIN_TTL_SECONDS"
        stop_app
        if start_app "$SHORT_TTL" ttl; then boot_ttl_ok=true; fi
        log "boot_ttl_ok=$boot_ttl_ok"
        $boot_ttl_ok && run_acceptance ttl "$SHORT_TTL_SECONDS"
    fi
else
    log "boot_ok=false (no jar)"
fi

# 4) 결과 병합: 두 단계 케이스를 합쳐 요구사항별 통과율을 다시 계산한다. 기동 실패 단계의 케이스는 실패로 센다.
echo "$tests_json" >"$LOG_DIR/tests.json"
echo "$coverage_json" >"$LOG_DIR/coverage.json"
python3 - "$OUT" "$NAME" "$assemble_ok" "$test_rerun" "$boot_ok" "$boot_ttl_ok" "$LOG_DIR" "$ACCEPTANCE" <<'PY'
import json, os, sys
out, name, assemble_ok, rerun, boot_ok, boot_ttl_ok, log_dir, acc_dir = sys.argv[1:9]
sys.path.insert(0, acc_dir)
import test_acceptance as ta

cases = []
for phase, booted in (("main", boot_ok), ("ttl", boot_ttl_ok)):
    path = os.path.join(log_dir, f"acceptance-{phase}.json")
    if booted == "true" and os.path.exists(path):
        cases += json.load(open(path))["cases"]
    else:
        in_phase = (lambda r: r != "R6") if phase == "main" else (lambda r: r == "R6")
        cases += [{"id": f.__name__, "req": r, "passed": False, "category": "boot",
                   "message": f"app did not boot for {phase} phase"} for r, f in ta.CASES if in_phase(r)]
result = {
    "run": name,
    "assemble_ok": assemble_ok == "true",
    "tests": json.load(open(os.path.join(log_dir, "tests.json"))),
    "test_rerun": rerun == "true",
    "coverage": json.load(open(os.path.join(log_dir, "coverage.json"))),
    "boot_ok": boot_ok == "true",
    "boot_ttl_ok": boot_ttl_ok == "true",
    "acceptance": ta.summarize(cases),
}
json.dump(result, open(out, "w"), ensure_ascii=False, indent=2)
a = result["acceptance"]
print(f"[grade:{name}] acceptance {a['passed']}/{a['total']} requirement_mean={a['requirement_mean']} "
      f"rates={a['requirement_rates']}")
PY
log "written $OUT"
