---
description: 현재 변경을 분석해 Conventional Commits 형식으로 논리 단위별 커밋을 만든다. 작업을 마치고 커밋할 때 사용.
argument-hint: [선택: 커밋 범위·의도 힌트]
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git commit:*), Bash(git restore:*)
---

## 현재 저장소 상태

- 현황: !`git status`
- 미스테이징 요약: !`git diff --stat`
- 미스테이징 변경: !`git diff`
- 이미 스테이징된 변경: !`git diff --staged`
- 최근 커밋(스타일·scope 참고): !`git log --oneline -15`

## 작업

위 변경을 분석해 **Conventional Commits 형식**으로, **논리 단위별로 나눠** 커밋한다.
사용자 힌트(있으면): $ARGUMENTS

### 절차
1. **변경 분석** — status/diff로 무엇이 왜 바뀌었는지 파악한다. `$ARGUMENTS`가 있으면 커밋 범위·의도 힌트로 반영한다.
2. **논리 단위 그룹핑** — 서로 무관한 변경(예: 기능 코드 + 무관한 문서 + 설정)은 별도 커밋으로 나눈다. 파일 단위로 그룹을 구성한다.
3. **그룹별 스테이징 → 커밋** — 각 그룹마다 해당 파일만 `git add <파일...>`로 스테이징하고 커밋한다. 한 그룹을 커밋한 뒤 다음 그룹으로 넘어간다. `git add .`로 전부 뭉뚱그리지 않는다.
4. **메시지 형식 (Conventional Commits 강제)**
   - `type(scope): subject`
   - type: `feat | fix | docs | style | refactor | perf | test | build | ci | chore | revert` 중 하나.
   - subject: 명령형, 소문자 시작, 마침표 없음, 72자 이내.
   - scope: 변경 영역(디렉터리/모듈)에서 추론하고, 애매하면 생략한다.
   - 필요 시 빈 줄 뒤 본문에 "무엇을/왜"를 불릿으로 적는다.
   - **Co-Authored-By 등 트레일러는 넣지 않는다.**
   - 메시지 언어는 최근 커밋(`git log`)의 관례를 따르되(한국어면 한국어), **형식은 항상 Conventional Commits를 유지**한다.

### 안전장치
- 스테이징할 변경이 전혀 없으면 커밋하지 말고 그대로 보고한다.
- **푸시하지 않는다** — 커밋만 담당한다(푸시는 별도 커맨드).
- `git reset --hard`, `--force` 등 파괴적 명령은 사용하지 않는다.
- pre-commit 훅이 실패하면: 훅이 파일을 자동 수정했으면 재스테이징 후 한 번 재시도하고, 코드 자체가 원인이면 정직하게 보고하고 중단한다. `--no-verify`로 훅을 우회하지 않는다.

### 보고
만든 커밋들을 `<해시> <메시지>` 한 줄씩 요약해 사용자에게 제시한다.
