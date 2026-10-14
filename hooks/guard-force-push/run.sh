#!/usr/bin/env bash
# PreToolUse(Bash): 강제 푸시(git push --force 류)를 사용자 승인 대상으로 돌린다.
# 차단(deny)하지 않고 permissionDecision "ask"로 사용자에게 묻는다.
#   대상: --force, -f(-uf 같은 묶음 포함), --force-with-lease, --force-if-includes, +refspec
# 실수 방지용이다. 별칭·변수 조합 같은 의도적 우회는 막지 않는다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
# 끄기: 환경변수 GUARD_FORCE_PUSH_OFF=1
# 의존: jq

set -eo pipefail

[[ "${GUARD_FORCE_PUSH_OFF:-}" == "1" ]] && exit 0

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
  [[ "${words[i]:-}" =~ ^(.*/)?git$ ]] || continue

  # git 전역 옵션(-C 경로, -c 키=값 등)을 건너뛴 첫 단어가 서브커맨드다
  j=$((i + 1))
  while (( j < ${#words[@]} )); do
    case "${words[j]}" in
      -C|-c|--git-dir|--work-tree|--namespace) j=$((j + 2)) ;;
      -*) j=$((j + 1)) ;;
      *) break ;;
    esac
  done
  [[ "${words[j]:-}" == push ]] || continue

  force=0
  for w in "${words[@]:j+1}"; do
    case "$w" in
      --force|--force-with-lease*|--force-if-includes) force=1 ;;
      --*) ;;
      -*f*) force=1 ;;
      +*)  force=1 ;;
    esac
  done

  if (( force )); then
    jq -n --arg r "강제 푸시는 원격 이력을 덮어씁니다. 실행할지 확인하세요: ${seg}" \
      '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "ask", permissionDecisionReason: $r}}'
    exit 0
  fi
done < <(printf '%s\n' "$cmd" | tr ';&|()`' '\n\n\n\n\n\n')

exit 0
