---
description: PR을 올리기 전에 브랜치 변경을 코드 리뷰한다. 테스트·린트로 검증하고 에이전트로 진단해 BLOCK/READY를 판정한다. PR은 만들지 않는다. 작업 브랜치를 PR로 올리기 직전에 사용.
argument-hint: [선택: base 브랜치, 추가 지시]
allowed-tools: Bash(git status:*), Bash(git branch:*), Bash(git rev-parse:*), Bash(git log:*), Bash(git diff:*), Bash(git remote:*), Bash(gh pr view:*), Bash(find:*), Bash(grep:*), Bash(echo:*), Agent
---

## 현재 저장소 상태

- 현황: !`git status -sb`
- 현재 브랜치: !`git rev-parse --abbrev-ref HEAD`
- 기본 base 후보: !`git rev-parse --abbrev-ref origin/HEAD 2>/dev/null || echo "(감지 실패 — 사용자에게 base 확인)"`
- base 이후 커밋: !`git log --oneline origin/HEAD..HEAD 2>/dev/null || echo "(base 감지 실패)"`
- base 대비 변경 요약: !`git diff --stat origin/HEAD...HEAD 2>/dev/null || echo "(base 감지 실패)"`
- 이 브랜치의 기존 PR: !`gh pr view --json number,url,state 2>/dev/null || echo "(없음)"`

## 작업

PR을 올리기 전에 현재 브랜치의 변경을 코드 리뷰한다. 검증·진단·판정까지만 하고 PR은 만들지 않는다. 사용자 지시(있으면): $ARGUMENTS

base는 `$ARGUMENTS`에 브랜치 이름이 있으면 그것을, 없으면 위 "기본 base 후보"를 쓴다. 아래에서 `{base}`로 부른다.

### 절차
1. **전제 확인** — 하나라도 걸리면 중단하고 보고한다.
   - 현재 브랜치가 `{base}`이거나 `main`/`master`이다 → 작업 브랜치를 만들라고 안내한다.
   - 커밋하지 않은 변경이 있다 → 무엇이 남았는지 보여 주고 `/commit`을 안내한다. 직접 커밋하지 않는다.
   - 이 브랜치에 열린 PR이 이미 있다 → URL을 보여 주고 `/post-pr`을 안내한다.
   - `{base}` 이후 커밋이 없다 → 리뷰할 것이 없다고 보고한다.
2. **검증** — 매니페스트(`build.gradle(.kts)`·`pom.xml`·`package.json`·`Makefile` 등)로 프로젝트의 테스트·린트·빌드 명령을 판별해 실행한다. 명령을 판별할 수 없으면 추측해 실행하지 말고 사용자에게 묻는다. 실패해도 진단은 계속하되, 결과는 판정에 반영한다.
3. **진단** — 아래 에이전트를 **한 메시지에서 병렬로** `Agent`로 스폰한다. 대상은 `git diff {base}...HEAD`로 바뀐 파일로 한정하고, 프롬프트에 "진단만 하고 수정하지 마. file:line 근거와 심각도를 붙여 보고해"를 넣는다.
   - 항상: `code-reviewer`, `security-auditor`
   - 레이어·모듈 경계·의존 방향이 바뀌었을 때: `architecture-expert`
   - 쿼리·반복 처리·핫패스가 바뀌었을 때: `performance-optimizer`
4. **판정** — 검증 결과와 진단을 심각도 순으로 합치고 중복을 없앤다.
   - **BLOCK**: 테스트·빌드 실패, 또는 CRITICAL/HIGH 발견 → PR을 올리기 전에 고쳐야 할 목록을 보여 준다. 고칠지는 사용자가 정한다.
   - **READY**: 그 외 → PR을 올려도 된다고 보고한다. MEDIUM 이하는 "리뷰 포인트"로 함께 보여 준다.

### 안전장치
- **코드를 고치지 않는다** — 이 커맨드는 검증·진단·판정만 한다. 수정은 사용자가 정한 뒤 별도로 한다.
- **푸시하거나 PR을 만들지 않는다** — PR 생성은 사용자가 직접 한다.

### 보고
- 판정(BLOCK/READY)과 검증 결과(실행한 명령과 결과)
- 진단 요약 — 심각도별 개수와 주요 항목 `file:line`
- 리뷰 포인트 — MEDIUM 이하 진단, 리뷰어가 봐야 할 곳
