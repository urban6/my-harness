#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit): 테스트 먼저(TDD)를 강제한다.
# 프로덕션 소스를 수정하려 할 때, 대응하는 테스트 파일이 작업 트리에 변경(수정·신규)으로
# 잡혀 있지 않으면 차단하고 "실패하는 테스트부터 작성하라"고 Claude에게 알린다.
#
# 등록: 같은 폴더의 hook.json (__HOOK_DIR__ = 이 폴더의 절대 경로로 치환해 settings.json에 병합)
#
# 대응 테스트 판별(파일명 기준, 스택 비종속):
#   Foo.java / Foo.kt     → FooTest, FooTests, FooSpec, FooIT
#   foo.service.ts / .js  → foo.service.spec.ts, foo.service.test.ts
#   foo.py                → test_foo.py, foo_test.py
#   foo.go                → foo_test.go
#
# 끄기: 환경변수 TDD_GUARD_OFF=1
# 제외 추가: 환경변수 TDD_GUARD_SKIP (경로에 대한 정규식)
# 의존: jq, git

set -euo pipefail

[[ "${TDD_GUARD_OFF:-}" == "1" ]] && exit 0

input=$(cat)
file=$(jq -r '.tool_input.file_path // ""' <<<"$input")
[[ -n "$file" ]] || exit 0

# 프로덕션 소스 확장자만 대상
[[ "$file" =~ \.(java|kt|ts|js|py|go)$ ]] || exit 0

# 테스트 파일 자체, 테스트 디렉터리, 타입 선언은 통과
[[ "$file" =~ (Test|Tests|Spec|IT)\.(java|kt)$ ]] && exit 0
[[ "$file" =~ \.(spec|test)\.(ts|js)$ ]] && exit 0
[[ "$(basename "$file")" =~ ^test_.*\.py$|_test\.(py|go)$ ]] && exit 0
[[ "$file" =~ /(test|tests|__tests__|src/test)/ ]] && exit 0
[[ "$file" =~ \.d\.ts$ ]] && exit 0

# 테스트 대상이 아닌 진입점·설정·와이어링 파일은 통과
DEFAULT_SKIP='(Application\.(java|kt)|/main\.ts|\.module\.ts|\.config\.(ts|js)|__init__\.py)$|/(migrations?|config)/'
[[ "$file" =~ $DEFAULT_SKIP ]] && exit 0
[[ -n "${TDD_GUARD_SKIP:-}" && "$file" =~ $TDD_GUARD_SKIP ]] && exit 0

# git 저장소가 아니면 판단 불가 → 통과
dir=$(dirname "$file")
[[ -d "$dir" ]] || dir=$(jq -r '.cwd // "."' <<<"$input")
repo=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) || exit 0

# 대응 테스트 파일명 후보
name=$(basename "$file")
stem="${name%.*}"   # foo.service.ts → foo.service, Foo.java → Foo
ext="${name##*.}"
case "$ext" in
  java|kt) candidates="${stem}Test.${ext} ${stem}Tests.${ext} ${stem}Spec.${ext} ${stem}IT.${ext}" ;;
  ts|js)   candidates="${stem}.spec.${ext} ${stem}.test.${ext}" ;;
  py)      candidates="test_${stem}.py ${stem}_test.py" ;;
  go)      candidates="${stem}_test.go" ;;
esac

# 작업 트리에서 변경(수정·스테이징·신규)된 파일 중 후보가 있는지
changed=$(git -C "$repo" status --porcelain --untracked-files=all | sed -E 's/^.{3}//; s/.* -> //')
for c in $candidates; do
  if grep -qE "(^|/)${c//./\\.}$" <<<"$changed"; then
    exit 0
  fi
done

cat >&2 <<EOF
[TDD] '$name'을(를) 수정하기 전에 실패하는 테스트부터 작성하세요.
대응 테스트 파일(${candidates// /, }) 중 하나를 먼저 추가·수정해 실패(RED)를 확인한 뒤 구현하세요.
테스트 대상이 아닌 파일이면 사용자에게 TDD_GUARD_SKIP 설정을 요청하세요.
EOF
exit 2
