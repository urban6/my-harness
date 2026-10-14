#!/usr/bin/env bash
# PreToolUse(Write|Edit|MultiEdit|Bash): API 키·시크릿 노출을 차단한다.
#   Write·Edit·MultiEdit → 작성 내용에 알려진 키 형식이 있으면 막는다(하드코딩 방지)
#     AWS(AKIA…), OpenAI·Anthropic(sk-…), GitHub(ghp_… / github_pat_…),
#     Slack(xox?-…), Google(AIza…), PEM 개인키(-----BEGIN … PRIVATE KEY-----)
#   Bash → 시크릿을 대화 로그로 출력하는 명령을 막는다
#     env 단독, printenv 단독 또는 시크릿 이름 인자,
#     echo·printf로 $…KEY / $…TOKEN / $…SECRET / $…PASSWORD 출력
# 실수 방지용이다. 키 형식이 아닌 시크릿이나 의도적 우회는 막지 않는다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
# 끄기: 환경변수 BLOCK_SECRET_LEAK_OFF=1
# 의존: jq

set -eo pipefail

[[ "${BLOCK_SECRET_LEAK_OFF:-}" == "1" ]] && exit 0

input=$(cat)

KEY_RE='AKIA[0-9A-Z]{16}|(^|[^A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,}|xox[abprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
SECRET_NAME='[A-Z0-9_]*(KEY|TOKEN|SECRET|PASSWORD|PASSWD)[A-Z0-9_]*'

tool=$(jq -r '.tool_name // ""' <<<"$input")
case "$tool" in
  Write)     text=$(jq -r '.tool_input.content // ""' <<<"$input") ;;
  Edit)      text=$(jq -r '.tool_input.new_string // ""' <<<"$input") ;;
  MultiEdit) text=$(jq -r '[.tool_input.edits[]?.new_string] | join("\n")' <<<"$input") ;;
  Bash)      text="" ;;
  *)         exit 0 ;;
esac

if [[ "$tool" != Bash ]]; then
  if grep -Eq -e "$KEY_RE" <<<"$text"; then
    file=$(jq -r '.tool_input.file_path // ""' <<<"$input")
    cat >&2 <<EOF
[secret-leak] '${file}'에 API 키·개인키로 보이는 값을 쓰려고 합니다.
키 값은 코드에 넣지 말고 환경변수나 시크릿 저장소에서 읽도록 작성하세요(예시 파일에는 빈 값이나 자리표시자를 쓰세요).
EOF
    exit 2
  fi
  exit 0
fi

cmd=$(jq -r '.tool_input.command // ""' <<<"$input")
[[ -n "$cmd" ]] || exit 0

set -f  # 단어 분리 때 글롭 확장 방지

# ; & | ( ) ` 줄바꿈으로 나눈 단순 명령 단위로 검사
while IFS= read -r seg; do
  read -ra words <<<"$seg" || true
  # 접두어(sudo·xargs·VAR=값·옵션 등)를 건너뛰고 실제 명령어 위치를 찾는다
  i=0
  while (( i < ${#words[@]} )) && [[ "${words[i]}" =~ ^(sudo|xargs|command|exec|nohup|time|nice|-.*|[A-Za-z_][A-Za-z0-9_]*=.*)$ ]]; do
    i=$((i + 1))
  done
  name=${words[i]:-}
  name=${name##*/}
  args=("${words[@]:i+1}")

  leak=0
  case "$name" in
    env)
      if (( ${#args[@]} == 0 )); then leak=1; fi ;;
    printenv)
      if (( ${#args[@]} == 0 )); then
        leak=1
      else
        for a in "${args[@]}"; do
          if [[ "$a" =~ ^${SECRET_NAME}$ ]]; then leak=1; fi
        done
      fi ;;
    echo|printf)
      for a in "${args[@]}"; do
        if [[ "$a" =~ \$\{?${SECRET_NAME} ]]; then leak=1; fi
      done ;;
  esac

  if (( leak )); then
    cat >&2 <<EOF
[secret-leak] 시크릿을 출력할 수 있는 명령은 차단됩니다: ${seg}
값 대신 존재 여부만 확인하세요(예: [ -n "\$API_KEY" ] && echo set).
EOF
    exit 2
  fi
done < <(printf '%s\n' "$cmd" | tr ';&|()`' '\n\n\n\n\n\n')

exit 0
