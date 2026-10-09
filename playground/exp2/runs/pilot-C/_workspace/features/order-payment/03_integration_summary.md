# 03. 통합 요약 — order-payment

## 확정 스택
Java 21 · Spring Boot 3.5.16 · Spring Data JPA (Hibernate 6) · PostgreSQL · Flyway · JUnit 5 + Testcontainers(postgres:16-alpine) + WireMock 3.13.1 + Awaitility. 스택 스킬 없음(skill: null) → 프로젝트 관례를 따름.

## 산출물
| 구분 | 경로 |
|---|---|
| 요구 분해 | `_workspace/features/order-payment/00_requirements.json` (R1~R11 passes: true) |
| API 설계 + 정합 요약 | `_workspace/features/order-payment/01_api_design.md` |
| DB 설계 | `_workspace/features/order-payment/02_db_design.md` |
| 진행 기록 | `_workspace/features/order-payment/progress.md` |
| 테스트 출력 원문 | `_workspace/features/order-payment/test_output.txt` |
| 설정 | `src/main/resources/application.yml` (C4 환경 변수 플레이스홀더) |
| 마이그레이션 | `src/main/resources/db/migration/V1__init.sql` |
| 구현 | `src/main/java/com/example/order/{common,product,coupon,order,payment,idempotency}` |
| 테스트 | `src/test/java/com/example/order/` — R01~R11 테스트 클래스, `C3PriorityTest`, `OrderPaymentSmokeTest`, `support/AbstractIntegrationTest` |

## 핵심 설계 결정
- C3 오류 우선순위는 처리 순서로 보장한다: Bean Validation 및 교차 검증(400) → 멱등 키 선점(422 → 409) → 업무 트랜잭션(404 → 409 재고 → 409 쿠폰) → PG(402/503).
- 동시성은 비관적 락으로 처리한다. 락은 주문 → 상품(id 오름차순) → 쿠폰 순으로만 잡는다. 같은 사용자의 쿠폰 중복 사용은 부분 유니크 인덱스로 한 번 더 막는다.
- 결제는 주문 행 락을 쥔 채 PG를 호출한다(R10.5에서 PG 호출은 최대 1회). 503이면 롤백만으로 주문·재고·쿠폰이 원래대로 남는다. 거절은 커밋한 뒤 402를 반환한다.
- 멱등성은 `idempotency_keys`의 (scope, key) 유니크와 SHA-256 요청 지문으로 처리한다. 2xx만 저장하고, 오류가 나면 선점을 해제한다.
- 만료는 200ms 간격 스윕(`FOR UPDATE SKIP LOCKED`)으로 처리하고, 주문 하나당 트랜잭션 하나를 쓴다.
- 목록은 (createdAt desc, id desc) 키셋 페이징을 쓰고, 커서는 base64url 형식이다.
- 시각은 UTC로 바꾸고 마이크로초 단위로 잘라 저장·응답한다. JSON 숫자 시각과 문자열 필드의 숫자·불리언 강제 변환은 400으로 거부한다(F1).

## 검증 이력
- Phase 1: 설계 PM 검토를 1/3 사이클에서 승인했다.
- Phase 2: boundary-verifier가 4 경계면 모두 PASS로 판정했다. REDO는 0회다.
- Phase 3: 첫 실행은 276건 중 1건 실패였다(F1: 숫자 epoch 시각을 수용). backend-impl이 고친 뒤 verifier 판정은 PASS로 유지됐다. 회귀 테스트 2건을 추가했다.

## 테스트
- 명령: `./gradlew cleanTest test --console=plain` (= `./gradlew test`, 증분 생략 방지를 위해 cleanTest 포함)
- 결과: **278 tests, 278 passed, 0 failed, 0 skipped** (`build/test-results/test` XML 13개 집계). 출력 마지막 줄은 `BUILD SUCCESSFUL in 50s`다.

| 요구 | 테스트 클래스 |
|---|---|
| R1 | R01ProductApiTest |
| R2 | R02CouponApiTest (+ R06 만료 복원) |
| R3 | R03OrderCreateTest |
| R4 | R04IdempotencyTest |
| R5 | R05PaymentTest |
| R6 | R06OrderExpiryTest (TTL PT3S 컨텍스트) |
| R7 | R07CancelRefundTest |
| R8 | R08ShippingTest |
| R9 | R09OrderListTest |
| R10 | R10ConcurrencyTest |
| R11 | R11ErrorFormatTest (+ C3PriorityTest) |

## 미해결 (BLOCKED)
없음.

## 알려진 한계·해석
- 만료 시각은 지났지만 아직 스윕되지 않은 PENDING 주문의 결제·취소는 409 INVALID_STATE를 반환한다. 이런 주문은 논리적으로 EXPIRED로 본다.
- 결제·환불 중에는 주문 행 락을 최대 약 4초(connect 2s + read 2s) 보유한다.
- 업무 커밋 뒤 멱등 `complete` 전에 프로세스가 죽으면 키가 IN_PROGRESS로 남는다. 멱등 키 만료는 범위 밖이다.
- 0원 결제 주문을 환불할 때는 PG를 호출하지 않는다(paymentId 없음). 스펙에 명시가 없어 테스트는 REFUNDED 상태만 단언한다.
