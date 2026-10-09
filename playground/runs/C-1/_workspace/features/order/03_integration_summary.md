# 03 — 통합 요약: 주문(Order) + 재고 차감

## 확정 스택
| 항목 | 값 |
|---|---|
| language | Java 21 |
| framework | Spring Boot 3.x (build.gradle: 3.5.16) |
| persistence | JPA + Hibernate, PostgreSQL |
| migration | Flyway (`V1__create_product_order_tables.sql`) |
| test | JUnit 5 + Testcontainers(PostgreSQL, `postgres:16-alpine`) |
| testCommand | `./gradlew test` |
| skill | spring-boot |

## 완료 조건 판정
| 조건 | 결과 |
|---|---|
| `./gradlew test` 통과 | 충족 — 79 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESSFUL` (test-writer 실행 `./gradlew cleanTest test`) |
| R1~R8 각각을 검증하는 테스트 | 충족 — 아래 매핑표 |

## 요구사항 ↔ 테스트 매핑
| ID | 테스트 클래스 | 건수(클래스 기준) | 핵심 검증 |
|---|---|---|---|
| R1 | ProductApiTest | 15(R1·R2 합산) | 201+Location+본문, stock 0 허용, name null/누락/""/공백, price 0/음수/누락, stock -1/누락 → 400 |
| R2 | ProductApiTest | (위와 합산) | 200 본문, 없는 id 404, `/abc` 400 |
| R3 | OrderCreateApiTest | 19 | 201+Location+ORDERED, 재고 차감, 400 9종, 404, 409, **원자성(양방향 순서)**, 우선순위 400>404, 404>409 |
| R4 | OrderRetrieveCancelApiTest | 10(R4·R5 합산) | 응답 형태, totalPrice=Σ, unitPrice 스냅숏, createdAt ISO-8601, 404 |
| R5 | OrderRetrieveCancelApiTest | (위와 합산) | CANCELLED + 재고 복원, 재취소 409(추가 복원 없음), 404, `/abc` 400 |
| R6 | OrderListApiTest | 16 | 기본값, R4 형태 일치, createdAt DESC·id DESC, 페이징, 범위 밖 page → 빈 content, size 1/100 허용, 400 6종 |
| R7 | OrderConcurrencyTest | 2 | 20스레드 동시 HTTP → 201×10, 409×10, 최종 재고 0, 주문 행 10 |
| R8 | ErrorFormatTest | 13 | problem+json, type·title·status·detail, 깨진 JSON/본문 없음/타입 불일치 400, 404, 409, Accept: application/json에서도 problem+json |
| (스모크) | OrderApplicationSmokeTest, OrderFlowSmokeTest | 1 + 3 | backend-impl 작성, 유지 |

## 경계면 검증 (boundary-verifier)
| 경계면 | 최종 | 이력 |
|---|---|---|
| 1. 설계 ↔ 컨트롤러 | PASS | R1 FIX(R6 page×size int 오버플로 500) → R2 PASS |
| 2. DB 설계 ↔ 엔티티·마이그레이션 | PASS | R1 PASS |
| 3. 에러 계약 ↔ 예외 핸들러 | PASS | R1 PASS |
| 4. DTO nullable | PASS | R1 PASS |

REDO 0회, BLOCKED 없음 → 미해결 항목 없음.

## 핵심 설계 결정
- 400 → 404 → 409: 400은 웹 계층(바인딩·Jackson·Bean Validation·`@DistinctProductIds`)에서 서비스 진입 전에 판정하고, 서비스는 전체 상품 존재(404)를 확인한 뒤 재고(409)를 판정.
- R3·R7: 단일 트랜잭션 + `UPDATE products SET stock = stock - :q WHERE id = :id AND stock >= :q`, productId 오름차순 실행(데드락 회피), 영향 행 0 → 409 + 전체 롤백.
- R5: 주문 행 `SELECT … FOR UPDATE` → 상태 확인 → `stock = stock + :q` 원자 복원.
- unitPrice는 `order_items.unit_price` 스냅숏, totalPrice는 생성 시 `multiplyExact`/`addExact`로 계산해 저장.
- 설정: `spring.datasource.*` 표준 키(환경 변수 덮어쓰기 가능), `server.port: ${SERVER_PORT:8080}`, `ddl-auto: validate`.

## 알려진 사항·수용한 기본안
- `amount-overflow`(400)는 404 이후, 409 이전에 판정됨 — 요구사항 밖 극단 케이스(가격×수량 > 2^63)의 유일한 우선순위 예외. 이 경로는 R1~R8 테스트 범위 밖.
- [Q.] 기본안 채택: name ≤ 255자, productId ≤ 0 → 404, 응답 items 요청 순서 유지, 알 수 없는 요청 필드 무시, Location 절대 URI, 취소 후 totalPrice 불변.
- build.gradle `test` 태스크에 `testLogging`(passed/failed/skipped, exceptionFormat full) 추가 — 콘솔에 개별 결과 표시용.
- Gradle 경고: "Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0." — 빌드 실패 아님, 미조치.

## 산출물 경로
- `_workspace/features/order/00_requirements.json`, `01_api_design.md`, `02_db_design.md`, `progress.md`, `03_integration_summary.md`
- 메인: `src/main/java/com/example/order/{product,order,common/error,common/web}/**`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__create_product_order_tables.sql`
- 테스트: `src/test/java/com/example/order/{support/*, *Test.java}`
