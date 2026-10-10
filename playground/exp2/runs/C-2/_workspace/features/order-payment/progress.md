# progress — order-payment

확정 스택: Java 21 · Spring Boot 3.x(3.5.16) · JPA/Hibernate · PostgreSQL · Flyway · JUnit 5 + Testcontainers · `./gradlew test` · skill=`spring-boot`

## 요구사항 id
R1 상품 · R2 쿠폰 · R3 주문 생성·조회 · R4 멱등성 · R5 결제 · R6 결제 만료 · R7 취소·환불 · R8 배송 · R9 주문 목록 · R10 동시성 · R11 에러 포맷 — **전부 테스트로 검증 완료 (test-summary.md 매트릭스)**

## Phase 체크리스트
- [x] Phase 0 — 스택 확정(사용자 명시) · 00_requirements.json · progress.md
- [x] Phase 1 — backend-designer: 01_api_design.md · 02_db_design.md · 정합 요약 검토 (사이클 1/3, 통과)
- [x] Phase 2 — backend-impl(구현, 스모크 36/36) + boundary-verifier(4 경계면 PASS) + backend-designer(REDO 대기, 수신 0건)
- [x] Phase 3 — test-writer: 386 tests, 0 failures/errors/skipped · `./gradlew test --rerun-tasks` BUILD SUCCESSFUL · 03_integration_summary.md

## verifier 판정 누적
| # | 경계면 | 판정 | 비고 |
|---|---|---|---|
| 1 | 1 설계↔컨트롤러 | PASS | C3 순서, R4.5/R5.6/R6.2/R10.5/R9.5 확인 |
| 1 | 2 DB설계↔엔티티·마이그레이션 | FIX | Coupon.java:21 `unique = true` 누락(설계 §5.2) |
| 1 | 3 에러계약↔예외 핸들러 | FIX | GlobalExceptionHandler.java:136 — 프레임워크 400에 `errors[]` 누락 |
| 1 | 4 DTO nullable | PASS | null 직렬화·박싱 타입·UTC Z 확인 |
| 2 | 2 DB설계↔엔티티·마이그레이션 | PASS | FIX 반영 확인 |
| 2 | 3 에러계약↔예외 핸들러 | PASS | GlobalExceptionHandler.java:141-161 errors[] 채움 확인 |

최종: 경계면 1~4 PASS · FIX 2회 · REDO 0회(verifier 집계)

## 미해결 (BLOCKED)
(없음)

## PM 주석
- [NOTE.] Phase 1: §16 정합 요약 확인. 결정 항목([DECISION]) 14건, 한계([LIMIT]) 2건(IN_PROGRESS 잔존, 결제 키 재사용) — 모두 feature.md 범위 밖 사항이라 수용.
- [NOTE.] Phase 1: 계약 밖 오류(없는 경로 404 등)에 R11.3 표에 없는 code(RESOURCE_NOT_FOUND 등)를 쓰는 결정 — R1~R10 정의 오류가 아니므로 수용.
- [NOTE.] Phase 2: backend-impl 설계 이탈 2건(Hikari minimum-idle 2, PG 응답 관대 파싱) — 계약 영향 없음, 수용. awaitility 테스트 의존성 추가.
- [NOTE.] Phase 2: verifier 재검증은 코드 열람 기반(테스트 미실행). 실행 확인은 Phase 3에서 수행.
- [NOTE.] Phase 3: test-writer가 구현 버그 0건 보고(테스트 기대값 오류 1건 자체 수정). PM이 test-output.txt 원문(BUILD SUCCESSFUL)과 test-summary.md(미커버 없음)를 직접 확인.
