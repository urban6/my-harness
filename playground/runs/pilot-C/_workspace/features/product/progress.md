# progress — product (상품 등록·조회)

확정 스택: Java 21 / Spring Boot 3.x (실제 3.5.16) / JPA+Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers / `./gradlew test` / skill: spring-boot

## 요구사항
- [x] R1 상품 등록 — `ProductCreateApiTest` 24/24 통과
- [x] R2 상품 조회 — `ProductGetApiTest` 9/9 통과
- [x] R8 에러 포맷 (RFC 9457) — `ProblemDetailsApiTest` 36/36 통과

## Phase 체크리스트
- [x] Phase 0 — 요구 분해 (`00_requirements.json`)
- [x] Phase 1 — 설계 (`01_api_design.md`, `02_db_design.md`) — PM 검토 1/3 사이클에서 통과
- [x] Phase 2 — 구현·검증 — impl 스모크 3/3 통과, verifier 4 경계면 PASS (FIX 0, REDO 0)
- [x] Phase 3 — 테스트·통합 — 72/72 통과(`./gradlew clean` 후 새로 실행, EXIT_CODE=0), `03_integration_summary.md` 작성

## Phase 1 PM 검토
- [NOTE.] Phase 0의 `projectState: greenfield`는 오판이었다. PM이 `build.gradle.kts`/`pom.xml`만 확인하고 Groovy `build.gradle`은 확인하지 않았다. 기존 스타터(`build.gradle` Boot 3.5.16, wrapper, `com.example.order.OrderApplication`)가 있어 `00_requirements.json`을 정정했다.
- [NOTE.] `name @Size(max=255)`는 요구사항에 없던 제약으로, `VARCHAR(255)`에서 도출해 추가했다. 채택한다.
- [NOTE.] Jackson 엄격 모드를 채택한다.
- [NOTE.] 경로 id가 숫자가 아니면 400 `invalid-path-parameter`로 응답한다. 채택한다.
- 정합 요약 §5a·§5b 확인: 필드↔컬럼, 필수↔NOT NULL, 검증↔CHECK가 모두 일치한다.

## Phase 2 PM 검토
- [NOTE.] `backend-designer`는 새로 스폰하지 않았다. 이름으로 호출할 수 있고 SendMessage를 받으면 재개되므로 REDO 경로를 열어 두었다. 실제 REDO는 발생하지 않았다.
- [NOTE.] 테스트 인프라에서 설계와 다른 부분이 있다(`@TestConfiguration @Bean @ServiceConnection`). 계약에는 영향이 없다.
- [NOTE.] `handleTypeMismatch` 메시지는 범용화가 필요하다(향후 `@RequestParam` 추가 시).

## Phase 3 PM 검토
- [NOTE.] test-writer는 스모크 테스트를 유지하고 확장했다(재작성하지 않음). 프로덕션 코드는 수정하지 않았다.
- [NOTE.] 500 경로는 도달할 수 없어 테스트가 없다. 405/415는 계약 범위 밖이다.
- [NOTE.] PM이 test-writer 보고만 믿지 않고, 별도 실행으로 `./gradlew clean` 뒤 `./gradlew test`를 다시 돌려 결과를 확인했다.

## verifier 판정 누적
| # | 경계면 | 판정 | REDO 카운트(verifier 소유) | 비고 |
|---|---|---|---|---|
| 1 | API 설계 ↔ 컨트롤러 | PASS | 0/2 | |
| 2 | DB 설계 ↔ 엔티티·마이그레이션·설정 | PASS | 0/2 | |
| 3 | 에러 계약 ↔ 예외 핸들러 | PASS | 0/2 | |
| 4 | DTO nullable | PASS | 0/2 | |

## 미해결 (BLOCKED)
(없음)
