# 03. 통합 요약 — order-payment (주문 결제: 쿠폰 · 재고 예약 · 외부 PG)

## 확정 스택
Java 21 · Spring Boot 3.5.16 · Spring Data JPA + Hibernate · PostgreSQL · Flyway · JUnit 5 + Testcontainers(PostgreSQL) · skill=spring-boot
테스트 명령: `./gradlew test`

## 완료 조건 판정
| 조건 | 결과 | 근거 |
|---|---|---|
| `./gradlew test` 통과 | ✅ | `./gradlew clean` 후 `./gradlew test --console=plain` 실행, `BUILD SUCCESSFUL`, `EXIT=0` (`test_output.txt`) |
| R1~R11 각각을 검증하는 테스트 존재 | ✅ | 270 tests / 0 failures / 0 errors / 0 skipped (`test_counts.txt`), 요구 id별 테스트 클래스는 아래 표 참조 |

## 요구사항 → 테스트
| 요구 | 테스트 클래스 (tests) |
|---|---|
| R1 상품 | `R1ProductTest` (21), `ProductSmokeTest` (3) |
| R2 쿠폰 | `R2CouponRegistrationTest` (32), `R2CouponDiscountTest` (23, C1 포함), `R2CouponUsageTest` (13) |
| R3 주문 생성·조회 | `R3OrderCreationTest` (29) |
| R4 멱등성 | `R4IdempotencyTest` (23) |
| R5 결제 | `R5PaymentTest` (18), `OrderPaymentSmokeTest` (3) |
| R6 결제 만료 | `R6ExpiryTest` (10, TTL=PT3S) |
| R7 취소·환불 | `R7CancelRefundTest` (12) |
| R8 배송 | `R8ShippingTest` (7) |
| R9 주문 목록 | `R9OrderListTest` (29) |
| R10 동시성 | `R10ConcurrencyTest` (7) |
| R11 에러 포맷 | `R11ErrorFormatTest` (17) |
| C3 오류 우선순위 | `C3ErrorPriorityTest` (23) |

## 경계면 검증 (boundary-verifier)
| 경계면 | 최종 | 비고 |
|---|---|---|
| 1 설계 ↔ 컨트롤러 | PASS | |
| 2 DB 설계 ↔ 엔티티·마이그레이션 | PASS | FIX 1회: `OrderService` expiresAt µs 절삭 누락 → 수정 |
| 3 에러 계약 ↔ 예외 핸들러 | PASS | |
| 4 DTO nullable | PASS | |
누적 REDO 0 · BLOCKED 0 · 미해결 없음

## 핵심 설계 결정
- 패키지 루트 `com.example.order` (기존 `OrderApplication` 위치를 따름)
- 금액 `long`/BIGINT, 시각 `Instant`/TIMESTAMPTZ, 저장 전 µs 절삭, 응답은 UTC ISO-8601
- 멱등: `idempotency_records` UNIQUE(scope, idem_key), `INSERT … ON CONFLICT DO NOTHING` 선점, SHA-256 지문(userId+경로+정규화 본문), 2xx만 COMPLETED로 저장하고 오류 시 레코드 삭제
- 동시성: 비관적 락, 전역 순서 order → products(id asc) → coupon. 결제는 주문 행 락을 보유한 채 PG 호출(connect/read 2초) → 동시 결제 시 PG 호출 1회
- 만료: 250ms `@Scheduled` 스윕, 주문 1건당 트랜잭션 1개, `FOR UPDATE SKIP LOCKED`
- 목록: (created_at, id) 키셋 커서(base64url)
- 설정(C4): `SPRING_DATASOURCE_*`, `SERVER_PORT`(8080), `PAYMENT_GATEWAY_URL`, `ORDER_PAYMENT_TTL`(PT15M)

## 산출물 경로
- 설계: `_workspace/features/order-payment/00_requirements.json`, `01_api_design.md`, `02_db_design.md`
- 진행: `_workspace/features/order-payment/progress.md`
- 테스트 증빙: `_workspace/features/order-payment/test_output.txt`, `test_counts.txt`
- 코드: `src/main/java/com/example/order/**`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init.sql`
- 테스트: `src/test/java/com/example/order/**` (`support/IntegrationTestBase`, `support/FakePaymentGateway`)

## 알려진 제약·잔여 위험 (테스트 통과와 별개로 기록)
1. **R6.2 극단 경계**: 만료 직전에 시작한 결제가 PG 2초 타임아웃으로 503 롤백되면, EXPIRED 반영이 expiresAt 후 최대 약 2.25초까지 늦어질 수 있다. R5.6(503이면 불변)과 겹치는 지점이라 설계 결함으로 보지 않았으며, 이 경로는 테스트하지 않았다.
2. **쿠폰 기간 정각 경계 미검증**: `validFrom` 정각 포함과 `validUntil` 정각 미포함은 Clock 제어 컨텍스트가 필요해 테스트하지 않았다(기간 전·후 케이스는 검증함).
3. **명세 밖 기대값 3건**(설계 문서 근거): `quantity: 1.5` → 400 / 같은 결제 키에 다른 cardToken → 422 / 동시 결제의 패자 → 409 `INVALID_STATE`.
4. **명세가 정하지 않은 조합**: "같은 사용자 사용 중이면서 소진"인 쿠폰의 code(설계상 `COUPON_NOT_APPLICABLE` 우선)는 테스트하지 않았다.
5. 비정상 종료로 남은 IN_PROGRESS 멱등 레코드 정리는 범위 밖이다(명세상 "멱등 키 만료" 제외).
