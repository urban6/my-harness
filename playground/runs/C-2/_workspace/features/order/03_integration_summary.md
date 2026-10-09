# 03 통합 요약 — order (주문 + 재고 차감)

## 확정 스택
Java 21 / Spring Boot 3.5.16 / Spring Data JPA + Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers(postgres:16-alpine) / 테스트 명령 `./gradlew test` / skill: spring-boot
(근거: feature.md 명시 + build.gradle 일치)

## 완료 조건
| 조건 | 상태 | 근거 |
|---|---|---|
| ./gradlew test 통과 | ✅ | `./gradlew cleanTest test --console=plain` 종료 코드 0, BUILD SUCCESSFUL, 96 tests / 0 failures / 0 errors / 0 skipped |
| R1~R8 각각을 검증하는 테스트 | ✅ | 아래 매핑표 |

## 요구사항 ↔ 테스트
| ID | 테스트 스위트 (tests) |
|---|---|
| R1 상품 등록 | `ProductApiTest$R1_CreateProduct` (15), `ProductApiSmokeTest` |
| R2 상품 조회 | `ProductApiTest$R2_GetProduct` (3), `ProductApiSmokeTest` |
| R3 주문 생성 | `OrderApiTest$R3_CreateOrder` (15) — 원자성(4항목 중 1개 부족 시 재고 불변·주문 0건), 400>404>409 우선순위 포함 |
| R4 주문 조회 | `OrderApiTest$R4_GetOrder` (5) — 가격 변경 후 unitPrice 스냅샷 유지, totalPrice 합계 |
| R5 주문 취소 | `OrderApiTest$R5_CancelOrder` (5) + 동시 취소 5회 → 200×1/409×4, 재고 1회 복원 |
| R6 주문 목록 | `OrderApiTest$R6_ListOrders` (14) — createdAt 동률 시 id desc, 범위 밖 page/size 400 |
| R7 동시성 | `OrderConcurrencyTest` (4) — 재고 10, 20건 동시 → 201×10, 409×10, 최종 재고 0 |
| R8 에러 포맷 | `ProblemDetailsApiTest` (21) + 모든 에러 단정이 `assertProblem`(problem+json, type·title·status·detail) 통과 |

기타: `FlywaySmokeTest` (1), `OrderApiSmokeTest` (9)

## 핵심 설계 결정
- 동시성/원자성: productId 오름차순 `SELECT ... FOR UPDATE` 단일 쿼리 → 존재 확인(404) → 재고 확인(409) → 쓰기. READ COMMITTED 유지. 데드락 없음(전역 락 순서).
- 400 → 404 → 409: 400은 전부 컨트롤러 진입 전(Jackson 엄격 모드, Bean Validation, 커스텀 `@UniqueProductIds`, 메서드 검증)에서 판정.
- 에러: `GlobalExceptionHandler`(ResponseEntityExceptionHandler 상속)에서 7종 type(`https://example.com/problems/{slug}`)으로 매핑, Accept와 무관하게 `application/problem+json`.
- totalPrice는 저장하지 않고 계산, unitPrice는 스냅샷 저장, createdAt은 `timestamptz`/`Instant` 밀리초 절삭.
- 설정: `spring.datasource.*`·`server.port` 표준 속성 → `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SERVER_PORT` 환경 변수로 덮어쓰기(relaxed binding). password 기본값 없음.

## 경계면 검증 (boundary-verifier)
| 경계면 | 판정 |
|---|---|
| 1 설계 ↔ 컨트롤러 | PASS (1회차) |
| 2 DB 설계 ↔ 엔티티·마이그레이션 | PASS (1회차) |
| 3 에러 계약 ↔ 예외 핸들러 | PASS (1회차) |
| 4 DTO nullable | PASS (1회차) |
FIX 0, REDO 0, BLOCKED 0.

## 산출물 경로
- 설계: `_workspace/features/order/01_api_design.md`, `02_db_design.md`
- 마이그레이션: `src/main/resources/db/migration/V1__create_order_schema.sql`
- 설정: `src/main/resources/application.yml`
- 코드: `src/main/java/com/example/order/{common,product,order}/**`
- 테스트: `src/test/java/com/example/order/**` (support/TestcontainersConfig, support/AbstractApiTest, *SmokeTest, ProductApiTest, OrderApiTest, OrderConcurrencyTest, ProblemDetailsApiTest)

## 미해결 / 커버리지 밖 (통과로 간주하지 않음)
- BLOCKED 경계면: 없음.
- 테스트 미작성: 500 `internal-error` 경로(정상 입력으로 유발 불가), 계약 밖 프레임워크 오류(없는 경로 404, 405, 415, 406), 원문 외 추가 규칙 name 255자 초과 400.
- 환경 변수 덮어쓰기(SPRING_DATASOURCE_*, SERVER_PORT)는 Spring 표준 relaxed binding에 의존하며 별도 테스트는 없음.
- R7 동시성 테스트는 2~3회 연속 통과 확인. 대량 반복으로 flaky 여부까지 확인하지는 않음.
- 변경 사항은 커밋하지 않음.
