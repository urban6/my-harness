# 03. 통합 요약 — order-payment (주문 결제: 쿠폰 · 재고 예약 · 외부 PG)

## 확정 스택
Java 21 / Spring Boot 3.5.16 / Spring Data JPA (Hibernate 6) / PostgreSQL / Flyway / JUnit 5 + Testcontainers(`postgres:16-alpine`) — skill: `spring-boot`

## 완료 조건 판정
| 조건 | 결과 | 근거 |
|---|---|---|
| `./gradlew test` 통과 | **충족** | `test_output.txt` 1024행 `Results: SUCCESS (500 tests, 500 passed, 0 failed, 0 skipped)`, 1034행 `BUILD SUCCESSFUL in 1m 25s` |
| R1~R11 각각을 검증하는 테스트 | **충족** | `test_summary.md` 매핑표: R1.1 … R11.3, C1~C4 전부 1건 이상, 누락 없음 |

## 테스트 명령·결과
- 명령: `./gradlew cleanTest test --console=plain 2>&1 | tee _workspace/features/order-payment/test_output.txt`
- 결과: 500 tests / 500 passed / 0 failed / 0 skipped
- 클래스: R1ProductTest 32, R2CouponTest 77, R3OrderCreateTest 49, R4IdempotencyTest 29, R5PaymentTest 30, R6PaymentExpiryTest 13, R7CancelRefundTest 22, R8ShippingTest 14, R9OrderListTest 47, R10ConcurrencyTest 23, R11ErrorFormatTest 115, CommonConventionsTest 37, C4EnvironmentVariablesTest 2, smoke 10

## Phase 경과
| Phase | 결과 |
|---|---|
| 0 요구 분해 | `00_requirements.json` — 스택은 feature.md 명시 + build.gradle 일치 |
| 1 설계 | `01_api_design.md`, `02_db_design.md` — PM 검토 1회 만에 승인 |
| 2 구현·검증 | boundary-verifier가 4개 경계면 모두 PASS. FIX 2건(쿠폰 유효기간 마이크로초 정밀도 → 500, NUL 문자 입력 → 500)은 반영 후 재검증 완료. REDO 0, BLOCKED 0 |
| 3 테스트·통합 | test-writer가 R1~R11·C1~C4 테스트 작성, 500/500 통과, 구현 결함 0 |

## 핵심 설계 결정
- 재고 예약: 상품 id 오름차순 조건부 UPDATE(`stock - reserved >= qty`). 잠금 순서는 전 경로 공통으로 주문 → 상품 → 쿠폰
- 쿠폰: 쿠폰 행 잠금 → 같은 사용자 활성 주문 확인 → 조건부 UPDATE. 부분 유니크 인덱스 `ux_orders_active_user_coupon`이 최후 방어선
- 멱등: `idempotency_keys`에 선점 INSERT(별도 트랜잭션), 완료 기록은 비즈니스 트랜잭션 안에서 함께 커밋, 오류면 삭제
- 결제: PG 호출 동안 주문 행 `FOR UPDATE` 유지 → 동시 결제 시 PG 호출 1회. PG 타임아웃 2초
- 만료: 500ms 주기 스위퍼, `FOR UPDATE SKIP LOCKED`
- 목록: `(created_at, id)` keyset 커서, base64url 인코딩

## 산출물 경로
- 설계: `_workspace/features/order-payment/01_api_design.md`, `02_db_design.md`
- 구현: `src/main/java/com/example/order/**`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init.sql`
- 테스트: `src/test/java/com/example/order/{support,smoke,requirements}/**`
- 증거: `test_output.txt`(원문), `test_summary.md`(클래스별 집계·요구사항 매핑), `progress.md`

## 기록해 둘 점 (테스트로 고정하지 않은 해석 사항 — 확정은 사람 몫)
- [NOTE.] **0원 주문 환불**: totalPrice가 0인 PAID 주문을 취소하면 PG 환불 없이 REFUNDED가 된다. R5.7에 따라 PG 결제를 하지 않았으므로 환불할 paymentId가 없다. R7.3 문면("PG에 환불을 요청")과 해석이 다를 수 있다.
- [NOTE.] `name` 100자 제한은 UTF-16 코드 유닛 기준이다(이모지는 2자로 센다). NBSP(U+00A0)·전각 공백(U+3000)만으로 된 name은 공백으로 보지 않는다.
- [NOTE.] `quantity`에 JSON 문자열 `"2"`가 오면 숫자로 변환해 받는다. 명세에 정의가 없다.
- [NOTE.] `GET /api/coupons/A%00B`처럼 경로에 NUL이 있으면 Tomcat이 앱에 닿기 전에 400(HTML)으로 거부한다. problem+json이 아니지만 5xx도 아니다.
- [NOTE.] test-writer가 `build.gradle`의 `test` 블록에 `testLogging`과 집계 출력만 추가했다. 의존성은 바꾸지 않았다.

## 미해결(BLOCKED)
없음.
