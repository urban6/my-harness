#!/usr/bin/env bash
#
# install.sh — my_harness 자산을 ~/.claude 또는 프로젝트 .claude/ 로 심링크한다.
#
# 자산(agents·commands·skills)을 이 레포에서 관리하고 사용처로 심링크하면,
# 레포만 고쳐도 연결된 모든 곳에 즉시 반영된다.
#
# 사용법:
#   ./install.sh install   [--global | --project [PATH]] [--type agents|skills|commands] [NAME...] [--dry-run] [--force]
#   ./install.sh uninstall [--global | --project [PATH]] [--type agents|skills|commands] [NAME...] [--dry-run]
#   ./install.sh list      [--global | --project [PATH]]
#
#   --global            대상 = ~/.claude (기본값)
#   --project [PATH]    대상 = PATH/.claude (PATH 생략 시 현재 디렉터리)
#   --type TYPE         유형 한정(agents|skills|commands). 반복 지정 가능
#   NAME...             개별 자산 이름(확장자 없이). 예: debugger nestjs commit
#   --dry-run           실제 변경 없이 수행 예정 작업만 출력
#   --force             기존 파일/타 심링크를 백업(.bak) 후 교체 (install 한정)
#
set -euo pipefail

# 레포 루트 = 이 스크립트의 위치 (어디서 실행하든 동작)
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 링크 대상이 되는 자산 유형
ASSET_TYPES=(agents commands skills)

# --- 인자 파싱 --------------------------------------------------------------
CMD="${1:-}"
case "$CMD" in
  install|uninstall|list) shift ;;
  ""|-h|--help|help)
    # 상단 연속 주석 블록(shebang 제외)만 도움말로 출력
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
    exit 0 ;;
  *)
    echo "알 수 없는 명령: '$CMD' (install | uninstall | list)" >&2
    exit 1 ;;
esac

TARGET_BASE="$HOME/.claude"   # 기본: 전역
DRY_RUN=0
FORCE=0
SEL_TYPES=()
SEL_NAMES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --global)
      TARGET_BASE="$HOME/.claude"; shift ;;
    --project)
      # 다음 인자가 옵션/자산이름이 아니면 경로로 사용, 아니면 현재 디렉터리
      if [[ $# -ge 2 && "$2" != --* ]]; then
        TARGET_BASE="$(cd "$2" 2>/dev/null && pwd || echo "$2")/.claude"; shift 2
      else
        TARGET_BASE="$(pwd)/.claude"; shift
      fi ;;
    --type)
      [[ $# -ge 2 ]] || { echo "--type 뒤에 유형이 필요합니다." >&2; exit 1; }
      SEL_TYPES+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --force)   FORCE=1; shift ;;
    --*) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
    *)   SEL_NAMES+=("$1"); shift ;;
  esac
done

# 유형 미지정 시 전체
if [[ ${#SEL_TYPES[@]} -eq 0 ]]; then
  SEL_TYPES=("${ASSET_TYPES[@]}")
fi

# --- 카운터 ------------------------------------------------------------------
N_LINKED=0; N_ALREADY=0; N_SKIPPED=0; N_REMOVED=0; N_MISSING=0

# 이름 필터: SEL_NAMES가 비었으면 항상 통과
name_selected() {
  [[ ${#SEL_NAMES[@]} -eq 0 ]] && return 0
  local n="$1" s
  for s in "${SEL_NAMES[@]}"; do [[ "$s" == "$n" ]] && return 0; done
  return 1
}

# 자산이 아닌 것 배제: README.md, *-workspace 디렉터리
is_excluded() {
  local base="$1"
  [[ "$base" == "README.md" ]] && return 0
  [[ "$base" == *-workspace ]] && return 0
  return 1
}

# 대상 하위 디렉터리 보장
ensure_dir() {
  local d="$1"
  [[ -d "$d" ]] && return 0
  if [[ $DRY_RUN -eq 1 ]]; then echo "  [dry-run] mkdir -p $d"; else mkdir -p "$d"; fi
}

# 한 자산 링크 (install)
link_one() {
  local src="$1" dst="$2" label="$3"
  # 이미 우리 레포를 가리키는 심링크면 멱등 skip
  if [[ -L "$dst" && "$(readlink "$dst")" == "$src" ]]; then
    echo "  already  $label"; N_ALREADY=$((N_ALREADY+1)); return
  fi
  # 그 외 무언가가 존재 → 충돌
  if [[ -e "$dst" || -L "$dst" ]]; then
    if [[ $FORCE -eq 1 ]]; then
      if [[ $DRY_RUN -eq 1 ]]; then
        echo "  [dry-run] replace $label (backup -> ${dst}.bak)"
      else
        rm -rf "${dst}.bak"; mv "$dst" "${dst}.bak"; ln -s "$src" "$dst"
      fi
      echo "  linked   $label (기존 -> ${dst##*/}.bak)"; N_LINKED=$((N_LINKED+1)); return
    fi
    echo "  CONFLICT $label — 기존 항목 유지, 건너뜀 (--force로 교체)"; N_SKIPPED=$((N_SKIPPED+1)); return
  fi
  # 신규 링크
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] ln -s $src $dst"
  else
    ln -s "$src" "$dst"
  fi
  echo "  linked   $label"; N_LINKED=$((N_LINKED+1))
}

# 한 자산 제거 (uninstall) — 우리 심링크만
unlink_one() {
  local src="$1" dst="$2" label="$3"
  if [[ -L "$dst" && "$(readlink "$dst")" == "$src" ]]; then
    if [[ $DRY_RUN -eq 1 ]]; then echo "  [dry-run] rm $dst"; else rm "$dst"; fi
    echo "  removed  $label"; N_REMOVED=$((N_REMOVED+1))
  elif [[ -e "$dst" || -L "$dst" ]]; then
    echo "  skip     $label — 우리 심링크가 아님, 유지"; N_SKIPPED=$((N_SKIPPED+1))
  else
    N_MISSING=$((N_MISSING+1))
  fi
}

# 유형별 소스 항목 순회 → (src, base, label) 로 콜백 실행
#   agents/commands: *.md 파일 / skills: 하위 디렉터리
for_each_asset() {
  local cb="$1" type src_dir base src
  for type in "${SEL_TYPES[@]}"; do
    src_dir="$REPO_ROOT/$type"
    [[ -d "$src_dir" ]] || continue
    if [[ "$type" == "skills" ]]; then
      for src in "$src_dir"/*/; do
        [[ -d "$src" ]] || continue
        src="${src%/}"; base="$(basename "$src")"
        is_excluded "$base" && continue
        name_selected "$base" || continue
        "$cb" "$type" "$src" "$base"
      done
    else
      for src in "$src_dir"/*.md; do
        [[ -f "$src" ]] || continue
        base="$(basename "$src")"
        is_excluded "$base" && continue
        name_selected "${base%.md}" || continue
        "$cb" "$type" "$src" "$base"
      done
    fi
  done
}

# --- 명령별 콜백 -------------------------------------------------------------
do_install() {
  local type="$1" src="$2" base="$3"
  local tdir="$TARGET_BASE/$type"
  ensure_dir "$tdir"
  link_one "$src" "$tdir/$base" "$type/$base"
}

do_uninstall() {
  local type="$1" src="$2" base="$3"
  unlink_one "$src" "$TARGET_BASE/$type/$base" "$type/$base"
}

do_list() {
  local type="$1" src="$2" base="$3"
  local dst="$TARGET_BASE/$type/$base" mark
  if [[ -L "$dst" && "$(readlink "$dst")" == "$src" ]]; then
    mark="✓ linked"
  elif [[ -e "$dst" || -L "$dst" ]]; then
    mark="⚠ 다른 항목"
  else
    mark="· 미설치"
  fi
  printf "  %-9s %s/%s\n" "$mark" "$type" "$base"
}

# --- 실행 --------------------------------------------------------------------
echo "레포:   $REPO_ROOT"
echo "대상:   $TARGET_BASE"
[[ $DRY_RUN -eq 1 ]] && echo "모드:   dry-run (변경 없음)"
echo

case "$CMD" in
  install)
    for_each_asset do_install
    echo
    echo "요약: linked=$N_LINKED  already=$N_ALREADY  conflict(skip)=$N_SKIPPED" ;;
  uninstall)
    for_each_asset do_uninstall
    echo
    echo "요약: removed=$N_REMOVED  skip=$N_SKIPPED" ;;
  list)
    for_each_asset do_list ;;
esac
