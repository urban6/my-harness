#!/usr/bin/env bash
# PreToolUse(Read|Grep|Bash): 민감 파일 읽기를 차단한다.
# 대상: .env, .env.* / *.pem, *.key, *.p12 / id_rsa, id_dsa, id_ecdsa, id_ed25519(개인키)
# 허용: .env.example, .env.sample, .env.template (값 없는 예시), id_*.pub (공개키)
#   Read      → file_path
#   Grep      → path
#   Bash      → 읽기 명령(cat·head·grep·source 등)의 인자, 또는 입력 리다이렉션(< .env)
# 실수 방지용이다. 변수 조합·eval 같은 의도적 우회는 막지 않는다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
# 끄기: 환경변수 BLOCK_SECRET_READ_OFF=1
# 의존: jq

set -eo pipefail

[[ "${BLOCK_SECRET_READ_OFF:-}" == "1" ]] && exit 0

input=$(cat)

# 민감 파일이면 0
is_secret() {
  local p=${1//\"/}
  p=${p//\'/}
  local b=${p##*/}
  [[ "$b" =~ ^\.env\.(example|sample|template)$ ]] && return 1
  [[ "$b" =~ ^\.env(\..+)?$ || "$b" =~ \.(pem|key|p12)$ || "$b" =~ ^id_(rsa|dsa|ecdsa|ed25519)$ ]]
}

block() {
  cat >&2 <<EOF
[secret-read] 민감 파일 '$1'은(는) 읽을 수 없습니다.
필요한 키 이름은 .env.example 등 예시 파일에서 확인하거나, 값 없이 키 목록만 알려 달라고 사용자에게 요청하세요.
EOF
  exit 2
}

case "$(jq -r '.tool_name // ""' <<<"$input")" in
  Read)
    f=$(jq -r '.tool_input.file_path // ""' <<<"$input")
    if [[ -n "$f" ]] && is_secret "$f"; then block "$f"; fi
    exit 0
    ;;
  Grep)
    f=$(jq -r '.tool_input.path // ""' <<<"$input")
    if [[ -n "$f" ]] && is_secret "$f"; then block "$f"; fi
    exit 0
    ;;
  Bash) ;;
  *) exit 0 ;;
esac

cmd=$(jq -r '.tool_input.command // ""' <<<"$input")
[[ -n "$cmd" ]] || exit 0

set -f  # 단어 분리 때 글롭 확장 방지

READERS='^(.*/)?(cat|head|tail|less|more|bat|grep|egrep|rg|ag|awk|sed|cut|sort|uniq|nl|xxd|od|hexdump|strings|base64|source|\.|diff|cmp|jq|yq|openssl)$'

# ; & | ( ) ` 줄바꿈으로 나눈 단순 명령 단위로 검사
while IFS= read -r seg; do
  read -ra words <<<"$seg" || true
  # 접두어(sudo·xargs·VAR=값·옵션 등)를 건너뛰고 실제 명령어 위치를 찾는다
  i=0
  while (( i < ${#words[@]} )) && [[ "${words[i]}" =~ ^(sudo|xargs|command|exec|nohup|time|nice|-.*|[A-Za-z_][A-Za-z0-9_]*=.*)$ ]]; do
    i=$((i + 1))
  done
  reader=0
  if [[ "${words[i]:-}" =~ $READERS ]]; then reader=1; fi

  prev=""
  for w in "${words[@]:i}"; do
    # 출력 리다이렉션 대상(> .env, >>.env)은 쓰기이므로 건너뛴다
    if [[ "$prev" =~ ^[0-9]*\>\>?$ || "$w" =~ ^[0-9]*\> ]]; then prev=$w; continue; fi
    # 입력 리다이렉션(< .env, <.env)은 명령과 상관없이 읽기다 (<< heredoc 제외)
    if [[ "$prev" == "<" ]] && is_secret "$w"; then block "$w"; fi
    if [[ "$w" == "<"?* && "$w" != "<<"* ]] && is_secret "${w#<}"; then block "${w#<}"; fi
    if (( reader )) && is_secret "$w"; then block "$w"; fi
    prev=$w
  done
done < <(printf '%s\n' "$cmd" | tr ';&|()`' '\n\n\n\n\n\n')

exit 0
