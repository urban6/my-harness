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

echo "# 확인에서 y/n 외 입력(한글 입력기 ㅜ) → 다시 묻기"
fresh; run_i "\\nn 1\\n\\n\\nㅜ\\nn\\n"
rc "exit 0" 0; has "잘못된 입력 안내" "잘못된 입력: ㅜ"; yes_ "대상 미생성" '[[ ! -e $H/.claude ]]'

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
