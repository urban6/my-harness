# 대화형 설치 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./install.sh`를 인자 없이 터미널에서 실행하면 대상·유형·항목을 골라 설치하는 대화형 모드를 추가하고, README 설치 섹션을 세 줄로 줄인다.

**Architecture:** `install.sh` 안에 `# --- interactive` 블록을 추가한다. 대상 선택(`interactive_target`)은 인자 파싱 직후, `SETTINGS_FILE`·`PLUGIN_SCOPE` 계산 전에 실행한다. 유형·항목 선택과 미리보기·확인·설치(`interactive_run`)는 실행부 `install)` 분기에서 기존 `for_each_asset do_install`을 유형별로 돌려 처리한다. 기존 함수는 고치지 않는다.

**Tech Stack:** bash 3.2(macOS `/bin/bash`), jq, claude CLI(plugins). 테스트는 의존성 없는 bash 시나리오 스크립트.

**Spec:** `docs/specs/2026-10-13-interactive-install.md`

## Global Constraints

- macOS 기본 bash 3.2에서 동작한다. 연관 배열(`declare -A`)과 `${var,,}`를 쓰지 않는다. 선택 상태는 인덱스 배열로 관리한다.
- `set -u` 아래에서 빈 배열 확장 오류가 나지 않도록 `${arr[@]+"${arr[@]}"}` 형태를 쓴다.
- 추가 의존성이 없다.
- 기존 비대화형 명령(`install`·`uninstall`·`list` + 플래그)의 동작과 출력이 바뀌지 않는다.
- 기존 함수(`link_one`, `do_install_*`, `for_each_asset` 등)는 고치지 않는다.
- 터미널 여부는 `[[ -t 0 ]]`로 판단하고, `HARNESS_INTERACTIVE=1`이면 이 검사를 건너뛴다.
- 입력 중 EOF(Ctrl-D)는 취소로 보고 exit 1로 종료한다.
- `CLAUDE.md`의 명령 섹션은 바꾸지 않는다.

## Review Focus

- 경로 입력에 `~/proj`를 넣으면 `$HOME/proj`로 펼쳐져야 한다(셸이 아니라 `read`라 자동으로 펼쳐지지 않는다).
- 경로 입력에 상대 경로(`proj2`)를 넣으면 현재 디렉터리 기준 절대 경로로 바뀌어 `대상:`과 "같은 명령"에 찍혀야 한다.
- 확인 질문에 대문자 `N`·`no`를 넣어도 취소돼야 한다.
- 충돌 질문에 Enter만 누르면 교체하지 않고(기본 N) 나머지는 설치를 진행해야 한다.
- 토글 입력의 앞뒤·중복 공백(`  n   1  `)과 앞자리 0(`08`)이 오류 없이 처리돼야 한다(`08`은 8진수 산술 오류가 나지 않고 번호 8로 해석).

---

### Task 1: 대화형 설치 모드

**Files:**
- Create: `tests/install-interactive.sh`
- Modify: `install.sh` (도움말 분기 41-44행, 기본값 블록 52-57행 부근, `SEL_TYPES` 기본값 블록 80-82행 뒤, 실행부 `install)` 분기)

**Interfaces:**
- Produces:
  - `ask PROMPT` → 전역 `REPLY`에 한 줄 입력. EOF면 "취소했습니다." 출력 후 exit 1
  - `interactive_target` → `TARGET_BASE`·`SETTINGS_NAME`·`IA_PROJECT`(프로젝트 절대 경로, 전역이면 빈 문자열) 설정
  - `interactive_toggle TITLE` → 전역 `TOGGLE_LABELS[]`·`TOGGLE_ON[]`(1/0)을 사용자 입력으로 갱신
  - `interactive_run` → 유형·항목 선택, 미리보기, 충돌·확인 질문, 설치, 요약 출력
  - 전역 `IA_TYPES[]`(고른 유형)·`IA_PICKS[]`(유형별로 고른 이름, 공백 구분)

- [ ] **Step 1: 회귀 기준 출력 저장 (변경 전)**

```bash
BASELINE=$(mktemp -d); echo "$BASELINE"
P=$(mktemp -d)
./install.sh install --dry-run > "$BASELINE/install-dry.txt" 2>&1
./install.sh install --project "$P" --dry-run 2>&1 | sed "s#$P#<P>#g" > "$BASELINE/project-dry.txt"
./install.sh list > "$BASELINE/list.txt" 2>&1
```

`BASELINE` 경로를 기억해 둔다(Task 2에서 비교).

- [ ] **Step 2: 실패하는 시나리오 테스트 작성**

`tests/install-interactive.sh`:

```bash
#!/bin/bash
# install.sh 대화형 모드 시나리오 테스트. 실행: /bin/bash tests/install-interactive.sh
# 임시 HOME에 설치하고, claude CLI는 호출을 기록하는 스텁으로 바꾼다.
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d)" && pwd)"; trap 'rm -rf "$WORK"' EXIT
STUB="$WORK/stub"; mkdir -p "$STUB"
cat > "$STUB/claude" <<'EOF'
#!/bin/bash
echo "$*" >> "$CLAUDE_LOG"
[[ "$*" == "plugin list --json" ]] && echo '[]'
exit 0
EOF
chmod +x "$STUB/claude"

FAIL=0; N=0
ok()   { echo "ok   $1"; }
bad()  { echo "FAIL $1"; FAIL=1; }
has()  { if grep -qF -- "$2" "$H.out"; then ok "$1"; else bad "$1 — 출력에 '$2' 없음"; fi; }
hasnt(){ if grep -qF -- "$2" "$H.out"; then bad "$1 — 출력에 '$2' 있음"; else ok "$1"; fi; }
rc()   { if [[ $RC -eq $2 ]]; then ok "$1"; else bad "$1 — exit $RC (기대 $2)"; fi; }
yes_() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

# 새 임시 HOME
fresh() { N=$((N+1)); H="$WORK/h$N"; mkdir -p "$H"; export CLAUDE_LOG="$H.log"; : > "$CLAUDE_LOG"; }
# run_i 입력(%b 이스케이프) [작업 디렉터리]
run_i() {
  (cd "${2:-$REPO}" && printf '%b' "$1" | HOME="$H" PATH="$STUB:$PATH" HARNESS_INTERACTIVE=1 /bin/bash "$REPO/install.sh") > "$H.out" 2>&1
  RC=$?
}
run_cli() { HOME="$H" PATH="$STUB:$PATH" /bin/bash "$REPO/install.sh" "$@"; }

ENTERS=""; for i in $(seq 30); do ENTERS="$ENTERS\\n"; done
SKILLS=$(cd "$REPO/skills" && for d in */; do d=${d%/}; [[ $d == *-workspace ]] || printf ' %s' "$d"; done)

echo "# 비터미널이면 도움말"
H="$WORK/nt"; /bin/bash "$REPO/install.sh" < /dev/null > "$H.out" 2>&1; RC=$?
rc "exit 0" 0; has "도움말 출력" "사용법"

echo "# Enter만 → 비대화형 install과 같은 결과"
fresh; run_i "$ENTERS"; HA=$H; LA=$CLAUDE_LOG
rc "exit 0" 0; has "요약 출력" "요약: linked="
has "같은 명령" "같은 명령: ./install.sh install --type agents --type commands --type rules --type skills --type hooks --type mods --type plugins"
fresh; HB=$H; run_cli install > /dev/null 2>&1
yes_ "list 결과 동일" '[[ "$(HOME=$HA PATH=$STUB:$PATH /bin/bash $REPO/install.sh list | tail -n +3)" == "$(HOME=$HB PATH=$STUB:$PATH /bin/bash $REPO/install.sh list | tail -n +3)" ]]'
yes_ "plugin install 호출 동일" '[[ "$(grep "^plugin install" $LA)" == "$(grep "^plugin install" $CLAUDE_LOG)" ]]'

echo "# 프로젝트 대상 → mods 없음"
P="$WORK/proj"; mkdir -p "$P"; fresh; run_i "2\\n" "$P"
rc "유형 단계 EOF → exit 1" 1; has "대상 표시" "대상:   $P/.claude"; has "agents 표시" "agents (10)"; hasnt "mods 없음" "mods ("

echo "# 유형·항목 일부 선택"
fresh; run_i "\\nn\\n1 4\\n\\nn\\n1\\n\\n\\n\\n"
rc "exit 0" 0
has "같은 명령" "같은 명령: ./install.sh install --type agents --type skills architecture-expert$SKILLS"
yes_ "고른 agent만 링크" '[[ "$(ls $H/.claude/agents)" == "architecture-expert.md" ]]'
yes_ "commands 미설치" '[[ ! -e $H/.claude/commands ]]'

echo "# 확인에서 n·N → 변경 없음"
for ans in n N no; do
  fresh; run_i "\\nn 1\\n\\n\\n$ans\\n"
  rc "$ans: exit 0" 0; has "$ans: 취소 안내" "취소했습니다"; yes_ "$ans: 대상 미생성" '[[ ! -e $H/.claude ]]'
done

echo "# 빈 선택"
fresh; run_i "\\nn\\n\\n"
rc "exit 0" 0; has "안내" "선택된 항목이 없습니다"

echo "# 잘못된 입력·공백·EOF"
fresh; run_i "\\n99 x 08\\n"
rc "EOF → exit 1" 1; has "99 거부" "잘못된 입력: 99"; has "x 거부" "잘못된 입력: x"; has "08 거부(범위 밖)" "잘못된 입력: 08"; has "취소 안내" "취소했습니다"
fresh; run_i "\\n  n   1  \\n\\n\\n\\n"
rc "공백 입력 exit 0" 0; yes_ "agents만 설치" '[[ -d $H/.claude/agents && ! -e $H/.claude/commands ]]'
fresh; run_i "9\\n"
rc "대상 EOF → exit 1" 1; has "대상 잘못된 입력" "잘못된 입력: 9"

echo "# 충돌"
fresh; mkdir -p "$H/.claude/agents"; echo x > "$H/.claude/agents/architecture-expert.md"
run_i "\\nn 1\\n\\nn 1\\n\\ny\\n\\n"
rc "y: exit 0" 0; has "y: 같은 명령에 --force" "같은 명령: ./install.sh install --type agents architecture-expert --force"
yes_ "y: 링크로 교체" '[[ -L $H/.claude/agents/architecture-expert.md && -f $H/.claude/agents/architecture-expert.md.bak ]]'
fresh; mkdir -p "$H/.claude/agents"; echo x > "$H/.claude/agents/architecture-expert.md"
run_i "\\nn 1\\n\\nn 1\\n\\n\\n\\n"
rc "Enter: exit 0" 0; hasnt "Enter: 같은 명령에 --force 없음" "architecture-expert --force"
yes_ "Enter: 기존 파일 유지" '[[ ! -L $H/.claude/agents/architecture-expert.md ]]'

echo "# 경로 입력"
fresh; mkdir -p "$H/proj"; run_i "3\\n$WORK/nope\\n~/proj\\n"
has "없는 경로 거부" "존재하지 않는 경로"; has "~ 펼침" "대상:   $H/proj/.claude"
mkdir -p "$WORK/proj2"; fresh; run_i "3\\nproj2\\nn\\n\\n" "$WORK"
rc "상대 경로: 빈 선택 exit 0" 0; has "상대 경로 → 절대" "대상:   $WORK/proj2/.claude"

echo
if [[ $FAIL -eq 0 ]]; then echo "ALL PASS"; else echo "SOME FAILED"; exit 1; fi
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `/bin/bash tests/install-interactive.sh`
Expected: `SOME FAILED`. "비터미널이면 도움말"은 지금도 통과하고, 대화형 시나리오는 도움말만 출력되므로 실패한다.

- [ ] **Step 4: 도움말 분기와 기본값 수정**

`install.sh` 상단 주석의 사용법 첫 줄 앞에 추가:

```bash
#   ./install.sh                     (인자 없이 터미널에서) 대화형: 대상·유형·항목을 골라 설치
```

인자 파싱의 도움말 분기(41-44행)를 다음으로 바꾼다.

```bash
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
```

`case "$CMD" in` 바로 위에 `INTERACTIVE=0`을 추가한다(`case` 안에서 1로 바뀐다). 그리고 기본값 블록(`SEL_NAMES=()` 다음 줄)에 추가:

```bash
IA_PROJECT=""                 # 대화형에서 고른 프로젝트 경로 (전역이면 빈 문자열)
```

- [ ] **Step 5: interactive 블록 추가**

`SEL_TYPES` 기본값 블록(`if [[ ${#SEL_TYPES[@]} -eq 0 ]]; then ... fi`) 바로 뒤에 추가:

```bash
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
collect_name() { IA_NAMES+=("$3"); }
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
  ask "진행할까요? [Y/n]: "
  case "$REPLY" in [nN]*) echo "취소했습니다. 변경 없음."; exit 0 ;; esac
  echo
  DRY_RUN=0; interactive_pass
  echo
  echo "요약: linked=$N_LINKED  already=$N_ALREADY  conflict(skip)=$N_SKIPPED"
}

if [[ $INTERACTIVE -eq 1 ]]; then interactive_target; fi
```

- [ ] **Step 6: 실행부에서 interactive_run 호출**

실행부 `case "$CMD" in`의 `install)` 분기 첫 줄에 추가:

```bash
  install)
    if [[ $INTERACTIVE -eq 1 ]]; then interactive_run; exit 0; fi
    for_each_asset do_install
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `/bin/bash tests/install-interactive.sh`
Expected: 마지막 줄 `ALL PASS`, exit 0.

- [ ] **Step 8: 커밋**

```bash
git add install.sh tests/install-interactive.sh
git commit -m "feat(install): 인자 없이 실행하면 대상·유형·항목을 고르는 대화형 설치"
```

### Task 2: README 정리와 회귀 확인

**Files:**
- Modify: `README.md` (`## 설치` 섹션)

**Interfaces:**
- Consumes: Task 1의 대화형 진입점(`./install.sh`), Step 1의 `$BASELINE`

- [ ] **Step 1: 회귀 비교**

```bash
P=$(mktemp -d)
diff "$BASELINE/install-dry.txt" <(./install.sh install --dry-run 2>&1)
diff "$BASELINE/project-dry.txt" <(./install.sh install --project "$P" --dry-run 2>&1 | sed "s#$P#<P>#g")
diff "$BASELINE/list.txt" <(./install.sh list 2>&1)
```

Expected: 세 `diff` 모두 출력 없음.

- [ ] **Step 2: README 설치 섹션 교체**

`README.md`의 `## 설치` 아래 코드 블록 전체를 다음으로 바꾼다.

````markdown
## 설치

```bash
./install.sh            # 대화형: 대상·유형·항목을 골라 설치
./install.sh list       # 설치 상태 확인
./install.sh uninstall  # 우리 심링크만 제거
```

스크립트에서 쓰거나 세부 옵션(`--type`, `--project`, `--force`, `--dry-run` 등)이 필요하면 `./install.sh -h`를 본다.
````

- [ ] **Step 3: 도움말 확인**

Run: `./install.sh -h | head -5`
Expected: 사용법에 `./install.sh                     (인자 없이 터미널에서) 대화형: 대상·유형·항목을 골라 설치` 줄이 있다.

- [ ] **Step 4: 커밋**

```bash
git add README.md
git commit -m "docs: README 설치 섹션을 대화형 설치 기준 세 줄로 정리"
```
