# order-payment 통합 요약 (Phase 3)

## 확정 스택
Java 21 · Spring Boot 3.5.16 · Spring Data JPA + Hibernate · PostgreSQL · Flyway(V1~V3) · JUnit 5 + Testcontainers(`postgres:16-alpine`) · PG 스텁은 JDK `HttpServer` · skill=null(프로젝트 관례). `build.gradle` 변경 없음, 새 의존성 없음.

## 산출물 경로 (프로젝트 루트: `/Users/jeonseungchul/SideProjects/my_harness/playground/exp3/runs/C-1`)
- 요구사항/진행: `_workspace/features/order-payment/00_requirements.json`, `progress.md`
- 설계: `01_api_design.md`, `02_db_design.md`
- 테스트 원본 출력: `test_output.txt`
- 마이그레이션: `src/main/resources/db/migration/V1__create_catalog_tables.sql`, `V2__create_order_tables.sql`, `V3__create_payments_table.sql`
- 코드: `src/main/java/com/example/order/` (`common/`, `config/`, `product/`, `coupon/`, `order/`, `payment/`)
- 테스트: `src/test/java/com/example/order/` (스모크 9개 클래스 확장 + `ConcurrencySafetyTest`, `PaymentGatewayFailureTest`, `OrderStateMatrixTest`, `SchemaConstraintsTest`, `OrderExpirySchedulerTest`, `support/`)

## 테스트 명령과 결과
명령: `cd /Users/jeonseungchul/SideProjects/my_harness/playground/exp3/runs/C-1 && ./gradlew test --rerun-tasks 2>&1 | tee _workspace/features/order-payment/test_output.txt`

- 최종 실행(R17 수정 후): **BUILD SUCCESSFUL in 24s** (`test_output.txt` 에서 PM 이 직접 확인).
- 테스트 개수: 228개 중 228 통과, 실패 0 · 에러 0 · 스킵 0. **이 수치는 backend-impl 이 `build/test-results/test/*.xml` 14개를 합산해 보고한 값이며, `test_output.txt` 에는 개수가 찍혀 있지 않아 PM 이 독립 검증하지는 못했다.** (test-writer 의 1차 실행에서는 동일 228개 중 227 통과/1 실패였다.)
- 1차 실행의 실패 1건(R17 `ConfigurationPropertiesTest.applicationYml_nonIsoTtl_isRejected`)은 프로덕션 결함이었고 수정됨: `OrderPaymentProperties` 의 Duration 5개가 Spring 의 관대한 변환기로 `900`→PT0.9S 등을 수용하던 것을 `Duration.parse` 엄격 파싱으로 교체. 테스트는 약화·`@Disabled` 없이 그대로 통과.
- 동시성 테스트는 test-writer 가 4회 반복 실행해 flaky 없음 확인, 의도적 코드 훼손(재고/쿠폰/claim 가드 제거)으로 테스트가 실제 회귀를 잡음을 확인.

## 경계면 검증 (boundary-verifier)
| 경계면 | 판정 | REDO |
|---|---|---|
| 1. API 설계 ↔ 컨트롤러 | PASS (FIX 1건 해결: 목록 lazy 만료 스윕) | 0 |
| 2. DB 설계 ↔ 엔티티·마이그레이션 | PASS | 0 |
| 3. 에러 계약 ↔ 예외 핸들러 | PASS | 0 |
| 4. DTO nullable + 설정 계약 | PASS (Phase 3 FIX: R17 TTL 엄격 파싱, 재검증 PASS) | 0 |

BLOCKED 경계면 없음. 단 verifier 는 도구 제약상 테스트를 직접 실행하지 않았고 정독·Grep 으로 판정했다.

## 요구사항 커버리지
R01~R19 모두 테스트 보유(test-writer 매트릭스 기준). R17 은 위 수정 후 통과.

## 설계 이탈 / 알려진 한계 / 미해결 (통과로 위장하지 않음)
1. Order/OrderItem/Payment 는 JPA 엔티티가 아니라 JdbcClient+record → `ddl-auto=validate` 가 이 3개 테이블을 검증하지 않는다. 스키마 정합은 마이그레이션이 설계와 일치한다는 점과 `SchemaConstraintsTest`(11개)에 의존.
2. `not-acceptable`(406) slug 가 구현에는 있으나 `01_api_design.md` §1.1 에 반영되지 않음(문서 정리 필요).
3. POST /orders·/pay 에서 `@Valid` 본문 검증이 헤더 검사보다 먼저 실행 → 본문·헤더 동시 오류 시 `validation-failed`(설계에 순서 규정 없음).
4. cancel/ship 완료 UPDATE 가 `lease_*` 컬럼을 비우지 않음(02 §3 (8),(13) 패턴 그대로). 만료된 리스라 무해하나 종결 주문에 잔존.
5. 설계 단계 [Q.] 기본 결정: PG 불확정 오류 후 cancel/만료 시 PG 측 이중 승인 가능성(정산 대사 범위 밖) · 결제 재시도 시 카드 변경 미지원 · 0원 주문도 `amount=0` 으로 PG 호출 · `maxDiscountAmount` null/0 = 상한 없음 · `PAYMENT_GATEWAY_URL` 개발용 기본값 `http://localhost:8081`(운영은 환경 변수 필수).
6. PAYMENT_FAILED 는 종결 상태(재결제 불허, 거절 시 재고·쿠폰 반환).
7. 이 세션에는 Task 도구가 없어 `progress.md` 만으로 추적했다.
