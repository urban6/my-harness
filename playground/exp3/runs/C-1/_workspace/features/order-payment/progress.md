# order-payment 진행 상황

- 기능 명세: `feature-short.md`
- 확정 스택: Java 21 / Spring Boot 3.5.16 / JPA+Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers / `./gradlew test` / skill=null
- 추적 메모: 이 세션에는 `TaskCreate`/`TaskUpdate`/`TaskList` 도구가 노출되어 있지 않아 이 파일이 유일한 추적 수단이다.

## 요구사항 id
R01~R19 (상세는 `00_requirements.json`)

## Phase 체크리스트
- [x] Phase 0 — 스택 확정, `00_requirements.json` 작성
- [x] Phase 1 — backend-designer 설계 / PM 검토 사이클 1/3 → 통과
- [x] Phase 2 — backend-impl + boundary-verifier — 4 경계면 PASS (REDO 0, BLOCKED 0)
- [x] Phase 3 — test-writer(R01~R19 전부 커버) → 1차 228개 중 1 실패(R17) → backend-impl FIX → 재실행 `BUILD SUCCESSFUL`(228/228, impl 합산 보고) → verifier 재검증 PASS → `03_integration_summary.md` 작성

## verifier 판정 누적 (최종)
| 경계면 | 판정 | REDO 횟수 | 이력 |
|---|---|---|---|
| 1. 설계 ↔ 컨트롤러 | PASS | 0 | FIX 1건(GET /orders?status=EXPIRED lazy 만료 스윕 누락) → 수정 → 재검증 PASS |
| 2. DB 설계 ↔ 엔티티·마이그레이션 | PASS | 0 | |
| 3. 에러 계약 ↔ 예외 핸들러 | PASS | 0 | |
| 4. DTO nullable + 설정 계약 | PASS | 0 | Phase 3 FIX(R17 TTL 엄격 ISO-8601) 후 재검증 PASS |

## 미해결 / BLOCKED
- BLOCKED: 없음.
- 닫힌 항목: R17 TTL 비-ISO 수용 결함 (수정·재실행 BUILD SUCCESSFUL 확인).
- 열린 비차단 항목 (통과로 위장하지 않고 기록 — 상세는 `03_integration_summary.md`):
  1. 본문 @Valid 가 헤더 검사보다 먼저 실행됨
  2. `not-acceptable`(406) slug 가 01_api_design.md §1.1 미반영
  3. orders/order_items/payments 가 ddl-auto=validate 대상 아님
  4. cancel/ship 후 lease_* 잔존(만료된 리스, 무해)
  5. 테스트 개수 228/228 은 impl 합산 보고이며 PM 독립 검증 불가(test_output.txt 에는 BUILD SUCCESSFUL 만 있음)
