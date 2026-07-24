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

## 에이전트 팀으로 돌리기

`~~ 개발해줘`처럼 두루뭉술하게 요청하면 메인 에이전트가 **혼자** 처리한다. 팀 오케스트레이션은 프롬프트에서 명시적으로 깨워야 하고, 반드시 **메인 세션에서 시작**해야 한다(서브에이전트는 추가 스폰이 막힘 — `agents/feature-pm.md`).

**1. 오케스트레이터 지목 (풀스택 기능 단위)** — `feature-pm`이 요구 분해 → 설계(api/ui/db) → 구현(backend/frontend) + 검증(boundary-verifier) → 테스트(test-suite)를 Phase 0~4로 스폰·조율한다. 워커끼리는 `SendMessage`로 대화하고, 산출물은 `_workspace/features/{name}/`에 쌓인다.

```
feature-pm 에이전트로 "로그인" 기능을 풀스택으로 개발해줘 —
요구 분해 → 설계 → 구현 → 검증 → 통합까지 Phase 파이프라인으로.
```

**2. 역할 직접 지정 (가벼운 협업)** — 정식 오케스트레이터 없이 프롬프트로 파이프라인을 엮는다.

```
결제 모듈 구현하고, 끝나면 security-auditor 감사 → test-writer 테스트 → code-reviewer 리뷰 순서로.
```

**3. 대규모 병렬 (Workflow)** — 전면 감사·마이그레이션 같은 fan-out. `ultracode` 키워드나 "워크플로우로 병렬 오케스트레이션"으로 opt-in(토큰 소모 큼).

> **팀을 부르는 신호**: ① 에이전트를 이름으로 지목(자동 트리거는 신뢰도 낮음) ② "병렬로/나눠서" 명시 ③ "구현 → 검증 → 테스트" 순서 명시 ④ 대규모면 `ultracode`.

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
