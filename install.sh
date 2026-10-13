#!/usr/bin/env bash
#
# install.sh — my_harness 자산을 ~/.claude 또는 프로젝트 .claude/ 로 심링크한다.
#
# 자산(agents·commands·rules·skills)을 이 레포에서 관리하고 사용처로 심링크하면,
# 레포만 고쳐도 연결된 모든 곳에 즉시 반영된다.
# hooks는 심링크 대신 hooks/<이름>/hook.json을 settings.json의 "hooks"에 병합한다
# (__HOOK_DIR__ → 레포 안 훅 폴더 절대 경로). run.sh 수정은 즉시 반영, hook.json 수정은 재설치 필요.
# mods는 mods/<이름> 경로를 ~/.claude/settings.json의 env.CLAUDE_CODE_PLUGIN_DIRS에 등록한다
# (전역 전용 — 프로젝트 settings의 env는 읽히지 않는다). 새 세션부터 로드된다.
# plugins는 plugins/<이름>.json의 외부 플러그인을 `claude plugin install`로 설치한다
# (전역 → user 스코프, --project → 그 프로젝트의 local 스코프). 마켓플레이스가 없으면 먼저 추가한다.
#
# 사용법:
#   ./install.sh                     (인자 없이 터미널에서) 대화형: 대상·유형·항목을 골라 설치
#   ./install.sh install   [--global | --project [PATH]] [--type agents|skills|commands|rules|hooks|mods|plugins] [NAME...] [--dry-run] [--force]
#   ./install.sh uninstall [--global | --project [PATH]] [--type agents|skills|commands|rules|hooks|mods|plugins] [NAME...] [--dry-run]
#   ./install.sh list      [--global | --project [PATH]]
#
#   --global            대상 = ~/.claude (기본값). hooks → ~/.claude/settings.json
#   --project [PATH]    대상 = PATH/.claude (PATH 생략 시 현재 디렉터리). hooks → settings.local.json
#                       (개인 레포 절대 경로가 커밋되지 않도록)
#   --type TYPE         유형 한정(agents|skills|commands|rules|hooks|mods|plugins). 반복 지정 가능
#   NAME...             개별 자산 이름(확장자 없이). 예: debugger nestjs commit
#   --dry-run           실제 변경 없이 수행 예정 작업만 출력
#   --force             기존 파일/타 심링크를 백업(.bak) 후 교체 (install 한정, hooks 무관)
#
# hooks·mods 설치·제거 시 settings 파일은 실행당 한 번 .bak로 백업한다. 의존: jq (plugins는 claude CLI도)
#
set -euo pipefail

# 레포 루트 = 이 스크립트의 위치 (어디서 실행하든 동작)
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 링크 대상이 되는 자산 유형
ASSET_TYPES=(agents commands rules skills hooks mods plugins)

# --- 인자 파싱 --------------------------------------------------------------
CMD="${1:-}"
INTERACTIVE=0
case "$CMD" in
  install|uninstall|list) shift ;;
  "")
    # 터미널이면 대화형 설치, 아니면 도움말 (테스트는 HARNESS_INTERACTIVE=1)
    if [[ -t 0 || "${HARNESS_INTERACTIVE:-}" == 1 ]]; then
      CMD=install; INTERACTIVE=1
    else
      awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
      exit 0
    fi ;;
  -h|--help|help)
    # 상단 연속 주석 블록(shebang 제외)만 도움말로 출력
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
    exit 0 ;;
  *)
    echo "알 수 없는 명령: '$CMD' (install | uninstall | list)" >&2
    exit 1 ;;
esac

TARGET_BASE="$HOME/.claude"   # 기본: 전역
SETTINGS_NAME="settings.json" # hooks 병합 대상 (--project면 settings.local.json)
DRY_RUN=0
FORCE=0
SEL_TYPES=()
SEL_NAMES=()
IA_PROJECT=""                 # 대화형에서 고른 프로젝트 경로 (전역이면 빈 문자열)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --global)
      TARGET_BASE="$HOME/.claude"; SETTINGS_NAME="settings.json"; shift ;;
    --project)
      SETTINGS_NAME="settings.local.json"
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

# --- interactive: 인자 없이 터미널에서 실행하면 대상·유형·항목을 골라 설치 ----
# 대상은 SETTINGS_FILE·PLUGIN_SCOPE 계산 전에 정하고, 유형·항목은 실행부에서 고른다.

# 한 줄 입력 → REPLY. EOF면 취소
ask() {
  printf '%s' "$1"
  read -r REPLY || { echo; echo "취소했습니다."; exit 1; }
}

set_project_target() { TARGET_BASE="$1/.claude"; SETTINGS_NAME="settings.local.json"; IA_PROJECT="$1"; }

interactive_target() {
  local cwd p
  cwd="$(pwd)"
  echo "설치 대상"
  echo "  1) 전역 $HOME/.claude (기본)"
  echo "  2) 현재 프로젝트 $cwd/.claude"
  echo "  3) 다른 프로젝트 경로 입력"
  while :; do
    ask "선택 [1]: "
    case "$REPLY" in
      ""|1) return ;;
      2) set_project_target "$cwd"; return ;;
      3)
        while :; do
          ask "프로젝트 경로: "
          p="$REPLY"
          [[ "$p" == "~" || "$p" == "~/"* ]] && p="$HOME${p:1}"
          if [[ -n "$p" && -d "$p" ]]; then set_project_target "$(cd "$p" && pwd)"; return; fi
          echo "존재하지 않는 경로: $REPLY"
        done ;;
      *) echo "잘못된 입력: $REPLY" ;;
    esac
  done
}

# TOGGLE_LABELS·TOGGLE_ON(1/0)을 번호로 토글. a=전체, n=없음, Enter=확정
interactive_toggle() {
  local title="$1" n=${#TOGGLE_LABELS[@]} i tok toks
  while :; do
    echo
    echo "$title — 번호로 토글, a=전체, n=없음, Enter=확정"
    for ((i = 0; i < n; i++)); do
      if [[ ${TOGGLE_ON[$i]} -eq 1 ]]; then printf '  [x] %d %s\n' $((i + 1)) "${TOGGLE_LABELS[$i]}"
      else printf '  [ ] %d %s\n' $((i + 1)) "${TOGGLE_LABELS[$i]}"; fi
    done
    ask "선택: "
    [[ -z "$REPLY" ]] && return
    read -r -a toks <<<"$REPLY"
    for tok in ${toks[@]+"${toks[@]}"}; do
      case "$tok" in
        a) for ((i = 0; i < n; i++)); do TOGGLE_ON[$i]=1; done ;;
        n) for ((i = 0; i < n; i++)); do TOGGLE_ON[$i]=0; done ;;
        *)
          if [[ "$tok" =~ ^[0-9]+$ ]] && (( 10#$tok >= 1 && 10#$tok <= n )); then
            i=$((10#$tok - 1)); TOGGLE_ON[$i]=$((1 - TOGGLE_ON[$i]))
          else
            echo "잘못된 입력: $tok"
          fi ;;
      esac
    done
  done
}

# 한 유형의 항목 이름 → IA_NAMES (for_each_asset의 제외 규칙을 그대로 따른다)
IA_NAMES=()
collect_name() { IA_NAMES+=("${3%.md}"); }   # md 자산은 확장자를 뗀 이름으로 고른다
list_names() { SEL_TYPES=("$1"); SEL_NAMES=(); IA_NAMES=(); for_each_asset collect_name; }

# 고른 유형마다 SEL_TYPES·SEL_NAMES를 좁혀 설치 (이름 필터가 유형을 구분하지 않으므로 유형별로 돌린다)
IA_TYPES=(); IA_PICKS=()
interactive_pass() {
  local i
  N_LINKED=0; N_ALREADY=0; N_SKIPPED=0; N_REMOVED=0; N_MISSING=0
  for ((i = 0; i < ${#IA_TYPES[@]}; i++)); do
    SEL_TYPES=("${IA_TYPES[$i]}")
    read -r -a SEL_NAMES <<<"${IA_PICKS[$i]}"
    for_each_asset do_install
  done
}

interactive_run() {
  local t i note sel cnt all_full=1 line types=() chosen=()
  # 1) 유형: 항목이 있는 유형만, 프로젝트면 mods 제외, 의존성이 없으면 표시하고 기본 해제
  TOGGLE_LABELS=(); TOGGLE_ON=()
  for t in "${ASSET_TYPES[@]}"; do
    [[ -n "$IA_PROJECT" && "$t" == mods ]] && continue
    list_names "$t"
    [[ ${#IA_NAMES[@]} -gt 0 ]] || continue
    note=""
    if [[ "$t" == hooks || "$t" == mods || "$t" == plugins ]] && ! command -v jq >/dev/null; then
      note=" (jq 필요)"
    elif [[ "$t" == plugins ]] && ! command -v claude >/dev/null; then
      note=" (claude CLI 필요)"
    fi
    types+=("$t"); TOGGLE_LABELS+=("$t (${#IA_NAMES[@]})$note")
    if [[ -z "$note" ]]; then TOGGLE_ON+=(1); else TOGGLE_ON+=(0); fi
  done
  interactive_toggle "설치할 유형"
  for ((i = 0; i < ${#types[@]}; i++)); do
    if [[ ${TOGGLE_ON[$i]} -eq 1 ]]; then chosen+=("${types[$i]}"); fi
  done

  # 2) 항목: 유형마다 토글. 전부 해제한 유형은 건너뛴다
  for t in ${chosen[@]+"${chosen[@]}"}; do
    list_names "$t"
    TOGGLE_LABELS=("${IA_NAMES[@]}"); TOGGLE_ON=()
    for ((i = 0; i < ${#IA_NAMES[@]}; i++)); do TOGGLE_ON+=(1); done
    interactive_toggle "── $t ──"
    sel=""; cnt=0
    for ((i = 0; i < ${#IA_NAMES[@]}; i++)); do
      if [[ ${TOGGLE_ON[$i]} -eq 1 ]]; then sel="$sel ${IA_NAMES[$i]}"; cnt=$((cnt + 1)); fi
    done
    [[ $cnt -gt 0 ]] || continue
    [[ $cnt -lt ${#IA_NAMES[@]} ]] && all_full=0
    IA_TYPES+=("$t"); IA_PICKS+=("${sel# }")
  done
  if [[ ${#IA_TYPES[@]} -eq 0 ]]; then echo "선택된 항목이 없습니다."; exit 0; fi

  # 3) 미리보기와 같은 명령
  echo; echo "── 미리보기 ──"
  DRY_RUN=1; interactive_pass
  line="./install.sh install"
  [[ -n "$IA_PROJECT" ]] && line="$line --project $(printf '%q' "$IA_PROJECT")"
  for t in "${IA_TYPES[@]}"; do line="$line --type $t"; done
  if [[ $all_full -eq 0 ]]; then
    for sel in "${IA_PICKS[@]}"; do line="$line $sel"; done
  fi

  # 4) 충돌이 있을 때만 교체 여부
  if [[ $N_SKIPPED -gt 0 ]]; then
    ask "기존 항목을 .bak으로 백업하고 교체할까요? [y/N]: "
    case "$REPLY" in [yY]*) FORCE=1; line="$line --force" ;; esac
  fi
  echo; echo "같은 명령: $line"

  # 5) 확인 후 설치
  while :; do
    ask "진행할까요? [Y/n]: "
    case "$REPLY" in
      ""|[yY]*) break ;;
      [nN]*) echo "취소했습니다. 변경 없음."; exit 0 ;;
      *) echo "잘못된 입력: $REPLY" ;;
    esac
  done
  echo
  DRY_RUN=0; interactive_pass
  echo
  echo "요약: linked=$N_LINKED  already=$N_ALREADY  conflict(skip)=$N_SKIPPED"
}

if [[ $INTERACTIVE -eq 1 ]]; then interactive_target; fi

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

# --- hooks: settings 파일 병합 ----------------------------------------------
SETTINGS_FILE="$TARGET_BASE/$SETTINGS_NAME"
SETTINGS_BACKED_UP=0

# 훅을 실제로 처리할 때만 jq 요구
require_jq() {
  command -v jq >/dev/null || { echo "hooks 처리에는 jq가 필요합니다." >&2; exit 1; }
}

# 현재 settings 내용 (없으면 빈 객체)
settings_read() {
  if [[ -f "$SETTINGS_FILE" ]]; then cat "$SETTINGS_FILE"; else echo '{}'; fi
}

# settings 저장 — 실행당 한 번 백업, 심링크된 settings도 유지되도록 덮어쓰기
settings_write() {
  local content="$1"
  ensure_dir "$TARGET_BASE"
  if [[ -f "$SETTINGS_FILE" && $SETTINGS_BACKED_UP -eq 0 ]]; then
    cp "$SETTINGS_FILE" "${SETTINGS_FILE}.bak"; SETTINGS_BACKED_UP=1
  fi
  printf '%s\n' "$content" > "$SETTINGS_FILE"
}

# 이 훅 폴더(src)를 가리키는 항목 제거. 빈 그룹·빈 이벤트·빈 hooks 객체도 정리
hook_strip() {
  jq --arg p "$1/" '
    if has("hooks") then
      .hooks |= with_entries(
          .value |= (map(.hooks |= map(select((.command // "") | startswith($p) | not)))
                     | map(select(.hooks | length > 0))))
      | .hooks |= with_entries(select(.value | length > 0))
      | if (.hooks | length) == 0 then del(.hooks) else . end
    else . end'
}

# hook.json의 __HOOK_DIR__를 src로 치환해 이벤트별로 추가
hook_add() {
  local src="$1"
  jq --arg d "$src" --slurpfile h "$src/hook.json" '
    ($h[0] | walk(if type == "string" then (split("__HOOK_DIR__") | join($d)) else . end)) as $add
    | .hooks = (.hooks // {})
    | reduce ($add | to_entries[]) as $e (.; .hooks[$e.key] = ((.hooks[$e.key] // []) + $e.value))'
}

do_install_hook() {
  local src="$1" label="$2" cur new
  require_jq
  cur=$(settings_read)
  # 기존 항목을 지우고 다시 추가 → 멱등이고 hook.json 변경도 반영됨
  new=$(hook_strip "$src" <<<"$cur" | hook_add "$src")
  if [[ "$(jq -S . <<<"$cur")" == "$(jq -S . <<<"$new")" ]]; then
    echo "  already  $label"; N_ALREADY=$((N_ALREADY+1)); return
  fi
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] merge $label -> $SETTINGS_FILE"
  else
    settings_write "$new"
  fi
  echo "  merged   $label"; N_LINKED=$((N_LINKED+1))
}

do_uninstall_hook() {
  local src="$1" label="$2" cur new
  require_jq
  [[ -f "$SETTINGS_FILE" ]] || { N_MISSING=$((N_MISSING+1)); return; }
  cur=$(settings_read)
  new=$(hook_strip "$src" <<<"$cur")
  if [[ "$(jq -S . <<<"$cur")" == "$(jq -S . <<<"$new")" ]]; then
    N_MISSING=$((N_MISSING+1)); return
  fi
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] remove $label from $SETTINGS_FILE"
  else
    settings_write "$new"
  fi
  echo "  removed  $label"; N_REMOVED=$((N_REMOVED+1))
}

# --- mods: env.CLAUDE_CODE_PLUGIN_DIRS 등록 ---------------------------------
# 값은 ':'로 이은 절대 경로 목록. 우리 경로만 더하고 빼며 다른 항목은 건드리지 않는다.
mod_strip() {
  jq --arg p "$1" '
    if (.env.CLAUDE_CODE_PLUGIN_DIRS // "") == "" then . else
      (.env.CLAUDE_CODE_PLUGIN_DIRS | split(":") | map(select(. != $p and . != ""))) as $rest
      | if ($rest | length) == 0 then del(.env.CLAUDE_CODE_PLUGIN_DIRS) else .env.CLAUDE_CODE_PLUGIN_DIRS = ($rest | join(":")) end
      | if (.env | length) == 0 then del(.env) else . end
    end'
}

mod_add() {
  jq --arg p "$1" '
    .env = (.env // {})
    | .env.CLAUDE_CODE_PLUGIN_DIRS = ([(.env.CLAUDE_CODE_PLUGIN_DIRS // "") | split(":")[] | select(. != "")] + [$p] | join(":"))'
}

mod_installed() {
  require_jq
  settings_read | jq -e --arg p "$1" '(.env.CLAUDE_CODE_PLUGIN_DIRS // "") | split(":") | index([$p]) != null' >/dev/null
}

# 전역이 아니면 mods는 건너뛴다 (CLAUDE_CODE_PLUGIN_DIRS는 ~/.claude/settings.json에서만 읽힘)
mod_global_only() {
  [[ "$TARGET_BASE" == "$HOME/.claude" && "$SETTINGS_NAME" == "settings.json" ]] && return 0
  echo "  skip     $1 — mods는 전역(--global) 설치만 지원"; N_SKIPPED=$((N_SKIPPED+1))
  return 1
}

do_install_mod() {
  local src="$1" label="$2" cur new
  mod_global_only "$label" || return 0
  require_jq
  cur=$(settings_read)
  if mod_installed "$src"; then
    echo "  already  $label"; N_ALREADY=$((N_ALREADY+1)); return
  fi
  new=$(mod_strip "$src" <<<"$cur" | mod_add "$src")
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] register $label -> $SETTINGS_FILE (env.CLAUDE_CODE_PLUGIN_DIRS)"
  else
    settings_write "$new"
  fi
  echo "  registered $label (새 세션부터 로드)"; N_LINKED=$((N_LINKED+1))
}

do_uninstall_mod() {
  local src="$1" label="$2" cur new
  mod_global_only "$label" || return 0
  require_jq
  [[ -f "$SETTINGS_FILE" ]] && mod_installed "$src" || { N_MISSING=$((N_MISSING+1)); return; }
  cur=$(settings_read)
  new=$(mod_strip "$src" <<<"$cur")
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] unregister $label from $SETTINGS_FILE"
  else
    settings_write "$new"
  fi
  echo "  removed  $label"; N_REMOVED=$((N_REMOVED+1))
}

# --- plugins: claude plugin CLI로 외부 플러그인 설치 -------------------------
# plugins/<이름>.json = { "id": "<플러그인>@<마켓플레이스>", "marketplace": "<owner/repo>"(선택) }
# 마켓플레이스는 항상 user 스코프에 추가한다. 플러그인 스코프는 전역 user, --project면 local.
PLUGIN_SCOPE="user"; PLUGIN_CWD="$HOME"
if [[ "$SETTINGS_NAME" == "settings.local.json" ]]; then
  PLUGIN_SCOPE="local"; PLUGIN_CWD="$(dirname "$TARGET_BASE")"
fi

require_claude() {
  require_jq
  command -v claude >/dev/null || { echo "plugins 처리에는 claude CLI가 필요합니다." >&2; exit 1; }
}

plugin_id() { jq -r '.id' "$1"; }

# 이 스코프(local이면 이 프로젝트)에 설치돼 있으면 0
plugin_installed() {
  local id="$1" proj
  proj="$(cd "$PLUGIN_CWD" 2>/dev/null && pwd -P || echo "$PLUGIN_CWD")"
  (cd "$PLUGIN_CWD" 2>/dev/null && claude plugin list --json) | jq -e --arg id "$id" --arg s "$PLUGIN_SCOPE" --arg p "$proj" \
    'any(.[]; .id == $id and .scope == $s and ($s != "local" or .projectPath == $p))' >/dev/null
}

do_install_plugin() {
  local src="$1" label="$2" id mkt
  require_claude
  id=$(plugin_id "$src"); mkt=$(jq -r '.marketplace // empty' "$src")
  if plugin_installed "$id"; then
    echo "  already  $label ($id, $PLUGIN_SCOPE)"; N_ALREADY=$((N_ALREADY+1)); return
  fi
  if [[ $DRY_RUN -eq 1 ]]; then
    [[ -n "$mkt" ]] && echo "  [dry-run] claude plugin marketplace add $mkt"
    echo "  [dry-run] claude plugin install $id --scope $PLUGIN_SCOPE"
  else
    # 이미 추가된 마켓플레이스면 add는 실패해도 무방하다
    [[ -n "$mkt" ]] && { claude plugin marketplace add "$mkt" >/dev/null 2>&1 || true; }
    (cd "$PLUGIN_CWD" && claude plugin install "$id" --scope "$PLUGIN_SCOPE" >/dev/null) \
      || { echo "  FAILED   $label ($id)"; N_SKIPPED=$((N_SKIPPED+1)); return; }
  fi
  echo "  installed $label ($id, $PLUGIN_SCOPE, 새 세션부터 로드)"; N_LINKED=$((N_LINKED+1))
}

do_uninstall_plugin() {
  local src="$1" label="$2" id
  require_claude
  id=$(plugin_id "$src")
  plugin_installed "$id" || { N_MISSING=$((N_MISSING+1)); return; }
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  [dry-run] claude plugin uninstall $id --scope $PLUGIN_SCOPE"
  else
    (cd "$PLUGIN_CWD" && claude plugin uninstall "$id" --scope "$PLUGIN_SCOPE" >/dev/null) \
      || { echo "  FAILED   $label ($id)"; N_SKIPPED=$((N_SKIPPED+1)); return; }
  fi
  echo "  removed  $label ($id, $PLUGIN_SCOPE)"; N_REMOVED=$((N_REMOVED+1))
}

# hook.json의 command가 settings에 모두 있으면 설치됨
hook_installed() {
  local src="$1"
  require_jq
  settings_read | jq -e --arg d "$src" --slurpfile h "$src/hook.json" '
    ([.hooks // {} | .[][] | .hooks[]? | .command] ) as $have
    | [$h[0][][] | .hooks[] | .command | split("__HOOK_DIR__") | join($d)]
    | all(. as $c | $have | index([$c]) != null)' >/dev/null
}

# 유형별 소스 항목 순회 → (src, base, label) 로 콜백 실행
#   agents/commands/rules: *.md 파일 / skills: 하위 디렉터리 / hooks: hook.json이 있는 하위 디렉터리
#   mods: .claude-plugin/plugin.json이 있는 하위 디렉터리 / plugins: *.json 파일
for_each_asset() {
  local cb="$1" type src_dir base src
  for type in "${SEL_TYPES[@]}"; do
    src_dir="$REPO_ROOT/$type"
    [[ -d "$src_dir" ]] || continue
    if [[ "$type" == "skills" || "$type" == "hooks" || "$type" == "mods" ]]; then
      for src in "$src_dir"/*/; do
        [[ -d "$src" ]] || continue
        src="${src%/}"; base="$(basename "$src")"
        [[ "$type" == "hooks" && ! -f "$src/hook.json" ]] && continue
        [[ "$type" == "mods" && ! -f "$src/.claude-plugin/plugin.json" ]] && continue
        is_excluded "$base" && continue
        name_selected "$base" || continue
        "$cb" "$type" "$src" "$base"
      done
    elif [[ "$type" == "plugins" ]]; then
      for src in "$src_dir"/*.json; do
        [[ -f "$src" ]] || continue
        base="$(basename "$src" .json)"
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
  [[ "$type" == "hooks" ]] && { do_install_hook "$src" "$type/$base"; return; }
  [[ "$type" == "mods" ]] && { do_install_mod "$src" "$type/$base"; return; }
  [[ "$type" == "plugins" ]] && { do_install_plugin "$src" "$type/$base"; return; }
  local tdir="$TARGET_BASE/$type"
  ensure_dir "$tdir"
  link_one "$src" "$tdir/$base" "$type/$base"
}

do_uninstall() {
  local type="$1" src="$2" base="$3"
  [[ "$type" == "hooks" ]] && { do_uninstall_hook "$src" "$type/$base"; return; }
  [[ "$type" == "mods" ]] && { do_uninstall_mod "$src" "$type/$base"; return; }
  [[ "$type" == "plugins" ]] && { do_uninstall_plugin "$src" "$type/$base"; return; }
  unlink_one "$src" "$TARGET_BASE/$type/$base" "$type/$base"
}

do_list() {
  local type="$1" src="$2" base="$3"
  local dst="$TARGET_BASE/$type/$base" mark
  if [[ "$type" == "hooks" ]]; then
    if hook_installed "$src"; then mark="✓ merged"; else mark="· 미설치"; fi
    printf "  %-9s %s/%s (%s)\n" "$mark" "$type" "$base" "$SETTINGS_NAME"
    return
  fi
  if [[ "$type" == "mods" ]]; then
    if [[ "$SETTINGS_NAME" == "settings.json" ]] && mod_installed "$src"; then mark="✓ registered"; else mark="· 미설치"; fi
    printf "  %-9s %s/%s (CLAUDE_CODE_PLUGIN_DIRS)\n" "$mark" "$type" "$base"
    return
  fi
  if [[ "$type" == "plugins" ]]; then
    require_claude
    if plugin_installed "$(plugin_id "$src")"; then mark="✓ installed"; else mark="· 미설치"; fi
    printf "  %-9s %s/%s (%s, %s)\n" "$mark" "$type" "$base" "$(plugin_id "$src")" "$PLUGIN_SCOPE"
    return
  fi
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
    if [[ $INTERACTIVE -eq 1 ]]; then interactive_run; exit 0; fi
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
