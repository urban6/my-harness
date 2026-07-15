# backend-feature — 백엔드 기능 개발 파이프라인

하나의 백엔드 기능 요청을 받아 **설계 → 데이터 모델 → 구현 → 테스트 → 리뷰 → 보안 감사**를 정해진 순서로 조율하는 오케스트레이션입니다.

- **무엇을 조율하나**: 백엔드 팀 에이전트를 **pipeline**(순차)으로 엮어, 앞 단계의 출력이 다음 단계의 입력이 되게 한다.
- **무엇을 검증하나**: 각 단계 끝에서 계약 충족 여부를 확인하고, 구현·리뷰·보안·성능 관점의 게이트를 통과시킨다.
- **무엇을 종합하나**: 최종적으로 동작하는 구현 + 테스트 + 리뷰/보안 지적 해소 상태를 하나의 결과로 모은다.

이 팀은 fan-out(병렬)보다 **pipeline(순차)** 이 기본이다. 계약이 단계마다 넘어가며 구체화되기 때문이다.

---

## 단계 구성

| # | 단계 | 에이전트 | 위치 | 핵심 입력 | 핵심 출력 |
| --- | --- | --- | --- | --- | --- |
| 1 | API 계약 설계 | `api-designer` | 이 저장소 | 리소스·유스케이스·제약 | 엔드포인트 표 / OpenAPI |
| 2 | 데이터 모델 설계 | `data-modeler` | 이 저장소 | 엔터티·접근 패턴 (+1의 리소스) | DDL / 마이그레이션 / 스키마 표 |
| 3 | 구현 | `backend-engineer` | 이 저장소 | 1의 `api_contract` + 2의 `data_model` | 코드 파일 + 빌드/테스트 검증 |
| 4 | 테스트 작성 | `test-writer` | 전역 재사용 | 3의 구현 파일·범위 | 단위/통합 테스트 |
| 5 | 코드 리뷰 | `code-reviewer` | 전역 재사용 | 3·4의 변경 diff | 품질·유지보수성 지적 |
| 6 | 보안 감사 | `security-auditor` | 전역 재사용 | 3의 변경 (auth/결제/PII 관련 시) | 보안 취약점 지적 |
| 7 | 성능 진단(선택) | `performance-optimizer` | 이 저장소 | 성능 우려 대상·증상 | 병목 findings·최적화 권고 |

전역 재사용 에이전트(`test-writer`·`code-reviewer`·`security-auditor`)는 `~/.claude/agents`에 이미 있으므로 이 저장소에서 다시 만들지 않는다.

---

## 단계 간 계약 매핑 (앞 출력 → 다음 입력)

- **1 → 2**: `api-designer.artifact`(리소스·엔드포인트) → `data-modeler.entities` / `access_patterns`의 근거.
- **1,2 → 3**: `api-designer.artifact` → `backend-engineer.api_contract`; `data-modeler.artifact` → `backend-engineer.data_model`.
- **3 → 4**: `backend-engineer.artifact`(구현 파일 목록) → `test-writer` 대상.
- **3,4 → 5**: 구현·테스트 변경 → `code-reviewer` 대상 diff.
- **3 → 6**: 구현 변경 → `security-auditor` (인증·인가·결제·사용자 데이터가 얽힐 때 **필수**).
- **3 → 7**: 성능 우려가 제기되면 `backend-engineer.summary`의 핫패스 → `performance-optimizer.target` / `symptom`.

---

## 게이트와 되돌림(rollback)

파이프라인은 단계 실패 시 **가장 가까운 책임 단계로 되돌린다.**

- 3의 `verification`이 실패(빌드/테스트 깨짐) → 3에서 수정. 원인이 계약 구멍이면 `open_questions`를 근거로 **1 또는 2로 되돌림**.
- 5의 **치명적** 지적 또는 6의 취약점 → **3으로 되돌려** 재구현 후 해당 단계 재실행.
- 7의 구조적 병목이 스키마/인덱스 문제 → **2로**, 코드 문제 → **3으로** 넘겨 실행.
- 2에서 파괴적 마이그레이션 경고가 나오면 진행 전 사용자 확인.

각 에이전트의 `open_questions`가 비어 있고 `verification`/`findings` 게이트를 통과해야 다음 단계로 넘어간다.

---

## 실행 방법

이 문서는 설계·계약을 정의한다. 실제 실행은 Claude Code의 `Workflow`가 `agentType`으로 각 에이전트를 순차 호출하는 형태로 구현한다. 개념적 골격:

```js
// pipeline: 단계마다 앞 산출물을 다음 입력으로 전달
const api    = await agent(designPrompt,   { agentType: 'api-designer',        schema: CONTRACT })
const model  = await agent(modelPrompt(api),{ agentType: 'data-modeler',        schema: SCHEMA })
const impl   = await agent(implPrompt(api, model), { agentType: 'backend-engineer' })
const tests  = await agent(testPrompt(impl),{ agentType: 'test-writer' })
const review = await agent(reviewPrompt(impl, tests), { agentType: 'code-reviewer' })
const sec    = await agent(secPrompt(impl), { agentType: 'security-auditor' })
// 성능 우려 시에만 7단계
```

> 우선 설계 문서로 둔다. 실행 가능한 JS `Workflow` 스크립트가 필요하면 이 골격을 `backend-feature.mjs` 등으로 확장한다.
