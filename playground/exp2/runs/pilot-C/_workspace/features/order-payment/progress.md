# progress — order-payment

확정 스택: Java 21 / Spring Boot 3.5.x / JPA + PostgreSQL / Flyway / JUnit 5 + Testcontainers / `./gradlew test` / skill: null

## 요구사항
R1 · R2 · R3 · R4 · R5 · R6 · R7 · R8 · R9 · R10 · R11 — 전부 passes: true (Phase 3 테스트 278/278 통과)

## Phase 0 — 요구 분해
- [x] 스택 확정 (feature.md 명시 + build.gradle 일치)
- [x] 00_requirements.json 작성

## Phase 1 — 설계 (backend-designer)
- [x] 01_api_design.md (+ 7장 정합 요약)
- [x] 02_db_design.md (V1__init.sql DDL 전문)
- [x] PM 검토 — 사이클 1/3에서 승인

PM 검토 주석:
- [NOTE.] C3 우선순위가 처리 순서(400 → 멱등 begin → 업무 tx 404 → 409 재고→쿠폰 → PG)로 강제됨. 승인.
- [NOTE.] R10.5는 주문 행 락을 쥔 채 PG 호출하는 방식 → 503 시 롤백만으로 R5.6 불변 충족. 락 보유 최대 4s, 풀 20 / open-in-view false 전제.
- [NOTE.] 만료 스윕 200ms + SKIP LOCKED, 결제·취소는 now ≥ expiresAt이면 409(전이는 스윕 전담).
- [NOTE.] 응답 시각은 UTC(Z)·μs truncate → 테스트는 Instant 비교 필요(test-writer에 전달).
- [NOTE.] spring.jackson.mapper.allow-coercion-of-scalars는 Jackson 버전에 따라 효과가 다를 수 있음 → 구현 시 실제 동작 확인 (→ Phase 3 F1로 이어짐).

## Phase 2 — 구현·검증 (backend-impl, boundary-verifier, backend-designer는 REDO 대기)
- [x] 구현 + 스모크 테스트 (backend-impl, ./gradlew test 스모크 2건 통과, Docker 사용 가능)
- [x] 경계면 1 설계 ↔ 컨트롤러 — PASS
- [x] 경계면 2 DB 설계 ↔ 엔티티·마이그레이션 — PASS
- [x] 경계면 3 에러 계약 ↔ 예외 핸들러 — PASS (런타임 FIX F1 반영 후 재검증 PASS 유지)
- [x] 경계면 4 DTO nullable — PASS

### verifier 판정 누적
| 회차 | 경계면 1 | 경계면 2 | 경계면 3 | 경계면 4 | FIX | REDO |
|---|---|---|---|---|---|---|
| 1 | PASS | PASS | PASS | PASS | 0 | 0 |
| 2 (F1 후 재검증, backend-impl 경유 보고) | — | — | PASS | — | 0 | 0 |

REDO 카운트(verifier 소유, 보고값): 1=0, 2=0, 3=0, 4=0

- [NOTE.] 비차단: RestClient 이름 붙은 빈 미사용(동작 동일), 커서 디코더의 추가 범위 검사(결과 동일).
- [NOTE.] 만료 시각이 지났지만 아직 스윕되지 않은 PENDING 주문의 결제·취소 → 409 INVALID_STATE(설계대로).

## Phase 3 — 테스트·통합 (test-writer)
- [x] R1~R11 테스트 (R01~R11 클래스 + C3PriorityTest + 스모크 유지)
- [x] ./gradlew test 통과 — 최종 278/278, BUILD SUCCESSFUL (test_output.txt)
- [x] 03_integration_summary.md

### Phase 3 FIX 기록
| # | 출처 | 내용 | 대상 | 상태 |
|---|---|---|---|---|
| F1 | R11ErrorFormatTest.numericTimestamp_400 | 시각 필드에 epoch 숫자 → 201 (기대 400, C2 위반) + 문자열 필드에 숫자 강제 변환 수용 | backend-impl (JacksonConfig 추가) | completed — 회귀 테스트 numericCouponCode_400·numericCardToken_400 추가 |

- [NOTE.] 첫 최종 실행은 동시에 돌던 다른 Gradle 프로세스와 build 디렉터리 XML 쓰기가 충돌해 BUILD FAILED(테스트 실패 아님) → 단독 재실행 통과.

## 미해결 (BLOCKED)
없음.
