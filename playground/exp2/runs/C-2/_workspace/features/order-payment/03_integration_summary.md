# 03. 통합 요약 — order-payment (주문 결제: 쿠폰 · 재고 예약 · 외부 PG)

## 확정 스택
Java 21 · Spring Boot 3.5.16 · Spring MVC · JPA(Hibernate 6) · PostgreSQL · Flyway · JUnit 5 + Testcontainers(`postgres:16-alpine`) · 스킬 `spring-boot`
테스트 명령: `./gradlew test`

## 결과
| 완료 조건 | 상태 | 근거 |
|---|---|---|
| `./gradlew test` 통과 | ✅ | `./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL in 1m 16s**. 386건 실행, failures 0 · errors 0 · skipped 0 (`test-output.txt`, `test-summary.md`). test-writer가 같은 명령을 2회 더 실행했고 모두 통과 |
| R1~R11 각각을 검증하는 테스트 | ✅ | `test-summary.md` 매트릭스: R1.1~R11.3 하위 항목 전부와 C1~C4. 미커버 없음 |

## Phase 경과
| Phase | 워커 | 결과 |
|---|---|---|
| 0 | (PM) | 스택은 feature.md에 명시된 것을 그대로 채택. build.gradle과 일치 확인 |
| 1 | backend-designer | 설계 1회 만에 통과(사이클 1/3) |
| 2 | backend-impl · boundary-verifier · backend-designer(REDO 대기) | 4 경계면 모두 PASS. FIX 2회(Coupon `unique=true`, 프레임워크 400의 `errors[]`), REDO 0회, BLOCKED 없음 |
| 3 | test-writer | 15개 요구사항 테스트 클래스를 추가하고 스모크 5개 클래스를 확장(재작성하지 않음). 구현 버그 0건, 테스트 쪽 기대값 오류 1건 수정 |

## 산출물 경로
- 설계: `_workspace/features/order-payment/01_api_design.md`, `02_db_design.md`
- 진행 기록: `_workspace/features/order-payment/progress.md`
- 테스트 결과: `_workspace/features/order-payment/test-output.txt`(원문), `test-summary.md`(클래스별 집계 + 커버리지 매트릭스)
- 코드: `src/main/java/com/example/order/{common,product,coupon,ordering,payment,idempotency}/**`
- 마이그레이션: `src/main/resources/db/migration/V1__init.sql`
- 설정: `src/main/resources/application.yml`. 오버라이드 가능 변수: `SPRING_DATASOURCE_*`, `SERVER_PORT`, `PAYMENT_GATEWAY_URL`, `ORDER_PAYMENT_TTL`
- 테스트: `src/test/java/com/example/order/{support,requirements}/**` + 스모크 5개 클래스

## 주요 설계 결정
- 동시성: 비관적 락을 전역 순서(orders → products(id 오름차순) → coupons)로 잡는다. 같은 사용자의 활성 쿠폰 사용은 부분 유니크 인덱스로 막는다. 결제는 주문 행 락을 쥔 채로 PG를 호출하므로 PG 호출은 최대 1회다.
- 멱등성: `(scope, key)`에 대한 `INSERT … ON CONFLICT`를 먼저 커밋하고, 2xx 응답은 비즈니스 트랜잭션 안에서 저장한다. 오류로 끝나면 기록을 삭제한다. 요청 지문은 SHA-256이다.
- 만료: 500ms 주기 스케줄러가 주문별 트랜잭션에서 `SKIP LOCKED`로 처리한다.
- 목록: `(createdAt, id)` 키셋 커서를 Base64URL로 인코딩한다.
- PG: RestClient + JDK HttpClient, 연결·읽기 타임아웃 각 2초.

## 알려진 한계 (범위 밖, 수용)
- `[LIMIT]` 처리 중 프로세스가 죽으면 IN_PROGRESS 멱등 기록이 남고, 그 키는 이후 계속 409를 받는다. 멱등 키 만료는 feature.md에서 제외된 범위다.
- `[LIMIT]` 오류로 끝난 결제 키를 다른 주문에 재사용하면 PG가 이전 결과를 돌려줄 수 있다. R5.3에 따라 키를 그대로 전달하기 때문이다.
- 계약 밖 오류(없는 경로, 405, 500)에는 R11.3 표에 없는 code(`RESOURCE_NOT_FOUND` 등)를 쓴다.

## 미해결 (BLOCKED)
없음.
