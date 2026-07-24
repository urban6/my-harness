# my-harness

**새 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code용 커스텀 에이전트·스킬·커맨드를 한곳에서 작성·검증·관리합니다. 여기 구성요소는 특정 프로젝트에 종속되지 않은 **재사용 기본값**이며, 새 프로젝트에는 심링크로 가져다 씁니다. **작성 → 검증 → 적용**의 순환으로 굴러갑니다.

## 구조

```
my-harness/
├── agents/     # 서브에이전트 정의 (.md, 파일 하나 = 에이전트 하나)
├── skills/     # 재사용 작업 절차 묶음 (SKILL.md + 참고 자료)
├── commands/   # 슬래시 커맨드 (/이름)
└── CLAUDE.md   # 이 레포 작업 시 지침
```

## 포함 구성요소

| 유형 | 자산 |
| --- | --- |
| **agents** | `feature-pm` · `api-designer` · `ui-designer` · `db-migrator` · `backend-impl` · `frontend-impl` · `boundary-verifier` · `test-suite` · `architecture-expert` · `debugger` · `performance-optimizer` |
| **skills** | `nestjs` · `spring-boot` (백엔드 관용 패턴 참조) |
| **commands** | `commit` · `push` |

> 상세 사용법은 각 파일의 frontmatter `description`을 참고하세요.

## 적용 방법

심링크를 쓰면 이 레포만 고쳐도 연결된 모든 곳에 바로 반영됩니다.

```bash
# 전역 — 모든 프로젝트에서 사용
ln -s ~/SideProjects/my_harness/agents/api-designer.md ~/.claude/agents/

# 프로젝트별 — 그 프로젝트에서만 사용
ln -s ~/SideProjects/my_harness/skills/spring-boot .claude/skills/spring-boot
```

## 컨벤션

- **네이밍**: kebab-case (`api-designer`).
- **description**: "무엇을 하는지"보다 **"언제 쓰는지"**를 적는다 — 자동 트리거 정확도를 좌우한다.
- **최소 권한**: 에이전트 `tools`는 실제 필요한 것만 나열한다.
- **커밋 단위**: 자산 하나 = 커밋 하나 (관련 변경은 함께).
