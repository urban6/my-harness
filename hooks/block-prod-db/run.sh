#!/usr/bin/env bash
# PreToolUse(Bash): Production DB 접속을 차단한다.
# DB 클라이언트(psql·mysql·mongosh·redis-cli 등)가 들어간 명령에서
#   ① prod / production / live 키워드가 단어로 보이거나 (예: prod-db.example.com, PROD_DATABASE_URL)
#   ② 환경변수 PROD_DB_PATTERN(프로젝트별 정규식, 대소문자 무시)과 일치하면 막는다.
# docker exec ... psql, kubectl exec -- psql 처럼 다른 명령 안에서 부르는 경우도 포함한다.
# 접속 정보가 변수 안에만 있는 경우(psql "$DATABASE_URL")는 판별하지 못한다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
# 프로젝트별 패턴: PROD_DB_PATTERN='db\.mycorp\.internal|10\.0\.1\.' (settings의 env에 둔다)
# 끄기: 환경변수 BLOCK_PROD_DB_OFF=1
# 의존: jq

set -eo pipefail

[[ "${BLOCK_PROD_DB_OFF:-}" == "1" ]] && exit 0

cmd=$(jq -r '.tool_input.command // ""')
[[ -n "$cmd" ]] || exit 0

set -f  # 단어 분리 때 글롭 확장 방지

CLIENTS='^(.*/)?(psql|pg_dump|pg_dumpall|pg_restore|mysql|mysqldump|mariadb|mongosh|mongo|mongodump|mongorestore|mongoexport|redis-cli|sqlcmd|sqlplus|clickhouse-client|cqlsh)$'
KEYWORD='(^|[^a-z0-9])(prod|production|live)([^a-z0-9]|$)'

# ; & | ( ) ` 줄바꿈으로 나눈 단순 명령 단위로 검사
while IFS= read -r seg; do
  read -ra words <<<"$seg" || true
  client=0
  for w in "${words[@]}"; do
    if [[ "$w" =~ $CLIENTS ]]; then client=1; break; fi
  done
  (( client )) || continue

  lower=$(tr '[:upper:]' '[:lower:]' <<<"$seg")
  if [[ "$lower" =~ $KEYWORD ]] || { [[ -n "${PROD_DB_PATTERN:-}" ]] && grep -Eiq -e "$PROD_DB_PATTERN" <<<"$seg"; }; then
    cat >&2 <<EOF
[prod-db] Production DB 접속으로 보이는 명령은 차단됩니다: ${seg}
로컬·스테이징 DB를 쓰세요. 꼭 필요하면 사용자가 직접 실행하도록 요청하세요.
오탐이면 사용자에게 BLOCK_PROD_DB_OFF=1 설정을 요청하세요.
EOF
    exit 2
  fi
done < <(printf '%s\n' "$cmd" | tr ';&|()`' '\n\n\n\n\n\n')

exit 0
