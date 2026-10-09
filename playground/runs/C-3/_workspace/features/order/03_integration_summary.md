# 03. 통합 요약 — order (주문 + 재고 차감)

## 확정 스택
Java 21 / Spring Boot 3.5.16 (web, validation, data-jpa) / JPA + Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers(postgres:16-alpine) — skill `spring-boot`
테스트 명령: `./gradlew test`

## 결과
- 완료 조건 1 `./gradlew test` 통과 — **충족** (BUILD SUCCESSFUL, 종료 코드 0, 91 tests / 0 failures / 0 errors / 0 skipped)
- 완료 조건 2 R1~R8 각각을 검증하는 테스트 존재 — **충족** (아래 매핑)
- 경계면 검증: 4/4 PASS (FIX 1, REDO 0, BLOCKED 0) — 미해결 없음

## 핵심 설계 결정
- 동시성(R3 원자성, R7): 단일 트랜잭션 안에서 productId 오름차순 조건부 UPDATE `stock = stock - :q WHERE id=:id AND stock >= :q`, 영향 행 0 → 409 + 전체 롤백. DB CHECK(stock ≥ 0)를 최후 방어선으로 둠.
- 취소(R5): 조건부 `UPDATE orders SET status='CANCELLED' WHERE id=:id AND status='ORDERED'` → 중복 취소 시 재고가 두 번 복원되지 않음.
- 400 → 404 → 409: 검사 단계 순서로 보장(인자 변환·역직렬화·Bean Validation → 존재 확인 → 조건부 UPDATE). 중복 productId는 `@AssertTrue`로 Bean Validation 단계에서 거름.
- 에러: `GlobalExceptionHandler extends ResponseEntityExceptionHandler`, 모든 에러를 `application/problem+json`으로 강제.
- 설정: 표준 `spring.datasource.*`, `server.port` 속성 → `SPRING_DATASOURCE_*`, `SERVER_PORT` 환경 변수로 덮어쓰기 가능. `ddl-auto=validate`, `open-in-view=false`.

## R ↔ 테스트 매핑 (src/test/java/com/example/order/)
| R | 테스트 클래스 | 건수(클래스 전체) |
|---|---|---|
| R1 상품 등록, R2 상품 조회 | ProductApiTest | 19 |
| R3 주문 생성(검증·404·409·원자성·우선순위) | OrderCreateApiTest | 19 |
| R4 주문 조회, R5 주문 취소 | OrderQueryCancelApiTest | 15 |
| R6 주문 목록(기본값·정렬·범위 400) | OrderListApiTest | 19 |
| R7 동시성(20스레드 → 201×10, 409×10, 재고 0) | OrderConcurrencyTest | 2 |
| R8 에러 포맷(400/404/409/JSON 파싱 실패) | ErrorFormatApiTest | 14 |
| 스모크(기동·Flyway validate·정상 경로·큰 page 회귀) | OrderSmokeTest | 3 |

## 산출물 경로
- 설계: `_workspace/features/order/01_api_design.md`, `02_db_design.md`
- 마이그레이션: `src/main/resources/db/migration/V1__create_products_and_orders.sql`
- 설정: `src/main/resources/application.yml`
- 코드: `src/main/java/com/example/order/{product,order,common}/**`
- 테스트: `src/test/java/com/example/order/{AbstractIntegrationTest, OrderSmokeTest, ProductApiTest, OrderCreateApiTest, OrderQueryCancelApiTest, OrderListApiTest, OrderConcurrencyTest, ErrorFormatApiTest}.java`

## 알려진 제약·메모
- 테스트 격리는 매 테스트 전 TRUNCATE에 의존 → 테스트 병렬 실행(maxParallelForks, JUnit parallel)을 켜면 깨짐. 현재는 순차 실행.
- 금액 합계 long 오버플로 400은 계약 밖 사례로, 우선순위 규칙의 예외로 문서화만 함(테스트 없음).
- 테스트 실행에 Docker가 필요(Testcontainers).
- verifier FIX#1: R6에서 page × size가 int 범위를 넘으면 500이 나던 문제 → 200 + 빈 content로 수정, 회귀 테스트 포함.
