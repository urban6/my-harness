#!/usr/bin/env bash
# PreToolUse(Bash): main/master 브랜치로의 직접 push를 차단한다.
#
# 등록 예 (settings.json):
#   "PreToolUse": [
#     { "matcher": "Bash",
#       "hooks": [{ "type": "command", "command": "~/SideProjects/my_harness/hooks/block-main-push.sh" }] }
#   ]
#
# 입력: stdin JSON의 .tool_input.command
# 출력: 차단 시 stderr에 사유 + exit 2 (Claude에게 전달됨), 통과 시 exit 0
# 의존: jq

set -euo pipefail

PROTECTED='^(main|master)$'

cmd=$(jq -r '.tool_input.command // ""')

# git push가 아니면 통과
[[ "$cmd" =~ (^|[[:space:];&|])git[[:space:]]+push([[:space:]]|$) ]] || exit 0

# git push 뒤의 인자만 추출(같은 줄의 && ; | 뒤 명령은 제외)하고 옵션(-u, --force 등)은 버린다
args=$(sed -E 's/.*git[[:space:]]+push//; s/[;&|].*//' <<<"$cmd")
read -ra positional <<<"$(tr ' ' '\n' <<<"$args" | grep -v '^-' | tr '\n' ' ')"

# 인자: [remote] [refspec...]. refspec이 없으면 현재 브랜치가 push 대상
if (( ${#positional[@]} >= 2 )); then
  targets=("${positional[@]:1}")
else
  targets=("$(git branch --show-current 2>/dev/null || true)")
fi

for ref in "${targets[@]}"; do
  dest="${ref##*:}"          # HEAD:main → main
  dest="${dest#refs/heads/}" # refs/heads/main → main
  if [[ "$dest" =~ $PROTECTED ]]; then
    echo "'$dest' 브랜치로의 직접 push는 금지입니다. 작업 브랜치를 만들어 PR로 올리세요." >&2
    exit 2
  fi
done

exit 0
