---
description: 현재 브랜치를 원격에 안전하게 푸시한다. 업스트림 설정·보호 브랜치 확인·강제 푸시 가드를 포함. 커밋을 원격에 올릴 때 사용.
argument-hint: [선택: 원격 이름 또는 추가 지시]
allowed-tools: Bash(git status:*), Bash(git branch:*), Bash(git rev-parse:*), Bash(git log:*), Bash(git remote:*), Bash(git push:*)
---

## 현재 저장소 상태

- 현황: !`git status -sb`
- 현재 브랜치: !`git rev-parse --abbrev-ref HEAD`
- 업스트림(없으면 비어 있음): !`git rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null`
- 원격 목록: !`git remote -v`
- 아직 안 올린 커밋: !`git log @{u}..HEAD --oneline 2>/dev/null`

## 작업

현재 브랜치를 원격에 푸시한다. 사용자 지시(있으면): $ARGUMENTS

### 절차
1. **푸시할 것이 있는지 확인** — 위 "아직 안 올린 커밋"이 비어 있고 업스트림이 이미 최신이면 푸시하지 말고 그대로 보고한다.
2. **업스트림 설정** — 업스트림이 없으면 `git push -u <원격> <브랜치>`로 현재 브랜치를 그대로 올린다. 원격 이름은 `$ARGUMENTS`에 있으면 그것을, 없으면 `origin`을 기본으로 한다.
3. **일반 푸시** — 업스트림이 있으면 `git push`.
4. **보고** — 푸시한 브랜치·원격·커밋 수를 요약해 사용자에게 제시한다.

### 안전장치
- **보호 브랜치 확인** — 현재 브랜치가 `main`/`master`이면 푸시 전에 사용자에게 확인받는다.
- **강제 푸시 금지(기본)** — 히스토리가 갈려 일반 푸시가 거부되면 임의로 강제하지 않는다. 강제가 정말 필요하면 **`--force-with-lease`만** 쓰고, 그 전에 원인과 위험을 설명한 뒤 사용자 확인을 받는다. `--force`(무조건 강제)는 쓰지 않는다.
- **비추적 원격 생성 금지** — 존재하지 않는 원격으로 푸시하지 않는다. 원격이 없으면 설정 방법을 안내만 하고 중단한다.
- 푸시가 거부되면(non-fast-forward 등) 이유를 정직하게 보고하고, `git pull --rebase` 등 사용자가 선택할 수 있는 다음 단계를 제시한다.
