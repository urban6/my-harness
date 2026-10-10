# 03. 통합 요약 — order-payment (주문 결제: 쿠폰 · 재고 예약 · 외부 PG)

## 확정 스택
Java 21 / Spring Boot 3.5.16 / JPA(Hibernate) + PostgreSQL / Flyway / JUnit 5 + Testcontainers(PostgreSQL). 스택 스킬 없음(skill=null).
테스트 명령: `./gradlew test`

## 산출물 경로 (ROOT = /Users/jeonseungchul/SideProjects/my_harness/playground/exp4/runs/pilot-C)
- 요구/진행: `_workspace/features/order-payment/{00_requirements.json, progress.md}`
- 설계: `_workspace/features/order-payment/{01_api_design.md, 02_db_design.md}`
- 구현: `src/main/java/com/example/order/{common,product,coupon,idempotency,order,payment}`, `src/main/resources/{application.yml, db/migration/V1__init_schema.sql}`
- 테스트: `src/test/java/com/example/order/**` (20개 suite, 396건) + `support/{PostgresTestConfig, IntegrationTestBase, ApiTestSupport, FakePaymentGateway, MutableClock, ClockTestConfig}`

## Phase 결과
| Phase | 결과 |
|---|---|
| 0 요구 분해 | 완료 |
| 1 설계 | PM 검토 통과 (사이클 1/3), 비차단 [Q.] 5건 설계자 기본 결정 승인 |
| 2 구현·검증 | 4 경계면 PASS (FIX 0, REDO 0, BLOCKED 0) |
| 2' 사후 FIX 1건 | Phase 3 중 발견: 숫자 `cardToken`이 400이 아닌 200 처리(설계 §0.3 편차) → `common/JacksonConfig.java`(엄격 String 역직렬화기)로 수정, 테스트 2건 추가 |
| 3 테스트 | R1~R11, C1~C4, PG 계약 전부 커버, 396건 통과 |

## 테스트 실행 결과 (`./gradlew clean test --console=plain`, 최종 코드 기준, exit 0)
```
> Task :clean
> Task :compileJava
> Task :processResources
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
> Task :test

2026-10-10T14:49:02.445+09:00  INFO 146 --- [order-service] [ionShutdownHook] o.s.b.w.e.tomcat.GracefulShutdown        : Commencing graceful shutdown. Waiting for active requests to complete
2026-10-10T14:49:02.447+09:00  INFO 146 --- [order-service] [tomcat-shutdown] o.s.b.w.e.tomcat.GracefulShutdown        : Graceful shutdown complete
2026-10-10T14:49:02.448+09:00  INFO 146 --- [order-service] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-10-10T14:49:02.448+09:00  INFO 146 --- [order-service] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
2026-10-10T14:49:02.449+09:00  INFO 146 --- [order-service] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.

BUILD SUCCESSFUL in 1m 7s
5 actionable tasks: 5 executed
```
`build/test-results/test/*.xml` 집계: 20개 suite, tests 396 / failures 0 / errors 0 / skipped 0.
(PM이 직접 확인한 XML: OrderConcurrencyTest 17, OrderPaymentTest 30, ProductApiTest 35, OrderExpiryTest 11, ErrorFormatTest 21, EnvironmentConfigTest 1 — 모두 failures/errors/skipped 0)

## 요구사항 → 대표 테스트
| id | 테스트 |
|---|---|
| R1 | `ProductApiTest` |
| R2 | `CouponApiTest`, `CouponApplicationTest`, `ClockBoundaryTest`, `DiscountCalculatorTest` |
| R3 | `OrderCreateTest` |
| R4 | `OrderIdempotencyTest` |
| R5 | `OrderPaymentTest`, `PaymentGatewayDownTest` |
| R6 | `OrderExpiryTest`, `ClockBoundaryTest` |
| R7 | `OrderCancelRefundTest`, `PaymentGatewayDownTest` |
| R8 | `OrderShippingTest` |
| R9 | `OrderListTest`, `OrderCursorTest`, `ClockBoundaryTest` |
| R10 | `OrderConcurrencyTest` (R10.1~R10.5, `@RepeatedTest(3)`) |
| R11 | `ErrorFormatTest` (code 13종 전수) |
| C1~C4 | 각 도메인 테스트의 `c1_*`, `TimeFormatTest`, `ErrorPriorityTest`, `EnvironmentConfigTest` |

## 한계·미검증·주의 (통과로 부풀리지 않음)
- **검증 방식**: boundary-verifier는 읽기 전용이라 빌드·테스트를 직접 실행하지 않았다(코드 읽기 근거). 숫자 `cardToken` FIX 후 verifier 재검증 PASS는 backend-impl의 전언이며 verifier 본인의 보고는 PM이 수신하지 못했다. 테스트 통과는 위 Gradle 실행으로 별도 확인됨.
- **C4 환경 변수**: 실제 OS 환경 변수 주입은 하지 못했다. 환경 변수와 같은 이름의 `SystemEnvironmentPropertySource`로 relaxed binding을 검증했다(`SERVER_PORT`는 실제 포트 바인딩까지 확인).
- **spec 미정의로 단언하지 않은 것**: 0원 PAID 주문의 환불 시 PG 호출 여부(설계: 미호출), `GET /api/orders?size=`(빈 값)은 400이 아니라 기본값으로 처리됨.
- **R10.5 패자 응답**: 409 `INVALID_STATE`는 설계자의 [Q.] 기본 결정이다(spec은 성공 1건만 규정).
- **시간 의존**: `OrderExpiryTest`는 실제 TTL(PT3S)을 기다려 약 36초 걸린다. PG 타임아웃 "2초대" 판정 범위(1.9~3.5초)는 극심한 부하 환경에서 흔들릴 수 있다.
- **환경 의존**: Testcontainers가 Docker를 요구한다.
- **참고(판정 무영향)**: PG가 100자 초과 `paymentId`를 주면 `orders.payment_id VARCHAR(100)` 초과로 500 가능(희박, 같은 키 재시도로 복구). `JacksonConfig`의 `modulesToInstall(module)`은 향후 Module 빈 추가 시 `modulesToInstall(Consumer)` 형태가 안전.
- **테스트 인프라 변경**: 기존 `PostgresTestConfig`의 `@ServiceConnection` 컨테이너가 `@DirtiesContext`로 컨텍스트가 닫힐 때 같이 멈추는 문제가 있어, 정적 컨테이너 + `DynamicPropertyRegistrar` 방식으로 바꿨다.
- **추적 도구**: 이 세션에서 Task 도구를 쓸 수 없어 `progress.md`로만 추적했다.

## 미해결(BLOCKED)
없음.
