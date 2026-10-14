#!/usr/bin/env bash
# PreToolUse(Bash): 재귀+강제 삭제(rm -rf 류)를 차단한다.
# rm에 재귀(-r/-R/--recursive)와 강제(-f/--force)가 함께 붙으면 막는다.
#   예) rm -rf x, rm -fr x, rm -r -f x, sudo rm -Rf x, find . | xargs rm -rf
# 실수 방지용이다. eval·변수 조합 같은 의도적 우회는 막지 않는다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
# 끄기: 환경변수 BLOCK_RM_RF_OFF=1
# 의존: jq

set -eo pipefail

[[ "${BLOCK_RM_RF_OFF:-}" == "1" ]] && exit 0

cmd=$(jq -r '.tool_input.command // ""')
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
  [[ "${words[i]:-}" =~ ^(.*/)?rm$ ]] || continue

  r=0 f=0
  for w in "${words[@]:i+1}"; do
    [[ "$w" == "--" ]] && break
    case "$w" in
      --recursive) r=1 ;;
      --force)     f=1 ;;
      --*)         ;;
      -*)
        if [[ "$w" == *[rR]* ]]; then r=1; fi
        if [[ "$w" == *f* ]]; then f=1; fi
        ;;
    esac
  done

  if (( r && f )); then
    cat >&2 <<EOF
[rm-rf] 재귀·강제 삭제(rm -rf 류)는 차단됩니다: ${seg}
삭제가 꼭 필요하면 대상 경로를 사용자에게 보여 주고 사용자가 직접 실행하도록 요청하세요.
EOF
    exit 2
  fi
done < <(printf '%s\n' "$cmd" | tr ';&|()`' '\n\n\n\n\n\n')

exit 0
