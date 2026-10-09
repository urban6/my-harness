# 03 통합 요약 — product (상품 등록·조회)

## 확정 스택
| 항목 | 값 |
|---|---|
| language | Java 21 |
| framework | Spring Boot 3.x (프로젝트 실제 3.5.16) |
| persistence | JPA + Hibernate, PostgreSQL |
| migration | Flyway (`V1__create_products.sql`) |
| test | JUnit 5 + Testcontainers (`postgres:16-alpine`, `@ServiceConnection`) |
| testCommand | `./gradlew test` |
| skill | spring-boot |

## 요구사항 결과
| ID | 내용 | 결과 | 검증 테스트 |
|---|---|---|---|
| R1 | POST /api/products → 201 + Location, 검증 위반 400 | PASS | `ProductCreateApiTest` (24) + 스모크 |
| R2 | GET /api/products/{id} → 200 / 404 | PASS | `ProductGetApiTest` (9) + 스모크 |
| R8 | RFC 9457 problem+json, type·title·status·detail, JSON 파싱 실패 400 | PASS | `ProblemDetailsApiTest` (36) |

## 완료 조건
- [x] `./gradlew test` 통과 — `./gradlew clean` 후 새로 실행: BUILD SUCCESSFUL, EXIT_CODE=0
- [x] R1·R2·R8 각각을 검증하는 테스트 존재

## 테스트 결과 (build/test-results/test/*.xml)
| 스위트 | tests | failures | errors | skipped |
|---|---|---|---|---|
| R1 상품 등록 (`ProductCreateApiTest`) | 24 | 0 | 0 | 0 |
| R2 상품 조회 (`ProductGetApiTest`) | 9 | 0 | 0 | 0 |
| R8 에러 포맷 (`ProblemDetailsApiTest`) | 36 | 0 | 0 | 0 |
| `ProductApiSmokeTest` | 3 | 0 | 0 | 0 |
| **합계** | **72** | **0** | **0** | **0** |

## 경계면 검증 (boundary-verifier)
| 경계면 | 판정 | REDO |
|---|---|---|
| API 설계 ↔ 컨트롤러 | PASS | 0/2 |
| DB 설계 ↔ 엔티티·마이그레이션·설정 | PASS | 0/2 |
| 에러 계약 ↔ 예외 핸들러 | PASS | 0/2 |
| DTO nullable | PASS | 0/2 |

미해결(BLOCKED): 없음.

## 산출물 경로
설계/추적 (`_workspace/features/product/`): `00_requirements.json`, `01_api_design.md`, `02_db_design.md`, `progress.md`, `03_integration_summary.md`

프로덕션 코드:
- `src/main/java/com/example/order/product/` — `Product`, `ProductRepository`, `ProductService`, `ProductController`, `ProductNotFoundException`, `dto/CreateProductRequest`, `dto/ProductResponse`
- `src/main/java/com/example/order/common/error/` — `GlobalExceptionHandler`, `ProblemTypes`
- `src/main/resources/db/migration/V1__create_products.sql`
- `src/main/resources/application.yml` (수정: datasource 표준 속성, password 키 없음, ddl-auto validate, Jackson 엄격 모드, server.port)

테스트 코드:
- `src/test/java/com/example/order/support/` — `PostgresTestConfig`, `AbstractIntegrationTest`, `ApiTestSupport`
- `src/test/java/com/example/order/product/` — `ProductApiSmokeTest`, `ProductCreateApiTest`, `ProductGetApiTest`, `ProblemDetailsApiTest`

## 요구사항 외 설계 결정 (PM 승인)
- `name` 최대 255자(`@Size`) — `VARCHAR(255)`에서 도출했다. 256자 이상은 400으로 응답한다.
- `price`는 `Long`/`BIGINT`로 21억 초과 금액을 허용한다.
- Jackson 엄격 모드: `"1000"`, `1000.5`, `true`는 400 malformed-request-body로 응답한다.
- 경로 id가 숫자가 아니면 400 invalid-path-parameter로, 0·음수면 404로 응답한다.
- name은 trim하지 않고 저장하며 중복을 허용한다. 요청 본문의 `id`는 무시한다.

## 알려진 제약 / 후속 과제
- 500(`about:blank`) 경로는 정상 요청으로 유발할 수 없어 테스트가 없다(설계 불변식상 도달 불가).
- 405/415 등 Spring 표준 예외는 problem+json으로 나가는 것은 확인했지만 계약 테스트 대상에서는 제외했다.
- `handleTypeMismatch`는 모든 TypeMismatch에 "경로 변수" 메시지를 쓴다. 향후 `@RequestParam`을 추가하면 분기가 필요하다.
- 02 §5.3의 테스트 컨테이너 선언 방식(`@Container static`)은 구현(`@TestConfiguration @Bean`)과 문구가 다르다. 계약에는 영향이 없다.
- 변경 사항은 커밋하지 않았다(working tree에만 존재).
