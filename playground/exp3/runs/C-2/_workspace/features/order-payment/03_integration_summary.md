# order-payment 통합 요약 (Phase 3)

## 확정 스택
Java 21 / Spring Boot 3.5.16 / Spring Data JPA + Hibernate + PostgreSQL / Flyway / JUnit 5 + Testcontainers(PostgreSQL 16) — skill: null(프로젝트 관례)

## 산출물
- 요구사항: `00_requirements.json` (REQ-01~17), `progress.md`
- 설계: `01_api_design.md`(정합 요약 포함), `02_db_design.md`
- 구현: `src/main/java/com/example/order/**`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init_schema.sql`
- 테스트: `src/test/java/com/example/order/**` (스모크 `OrderPaymentSmokeTest` 유지 + `api/*`, `support/*` 확장)

## 경계면 검증 (boundary-verifier)
| 경계면 | 판정 | FIX | REDO |
|---|---|---|---|
| API 설계 ↔ 컨트롤러 | PASS | 0 | 0 |
| DB 설계 ↔ 엔티티·마이그레이션 | PASS | 0 | 0 |
| 에러 계약 ↔ 예외 핸들러 | PASS | 0 | 0 |
| DTO nullable ↔ 컬럼 | PASS | 0 | 0 |
BLOCKED 없음. 설계 이탈 3건(클래스명 `PurchaseOrder`, `spring.web.locale=en`, cancel/ship/deliver `noRollbackFor`)은 수용.

## 테스트 명령·결과
명령: `./gradlew cleanTest test` (test-writer가 `./gradlew test`로 2회 통과 확인, 이후 backend-impl이 `cleanTest`로 독립 재실행)
결과: BUILD SUCCESSFUL in 26s — JUnit XML 합산 tests=235, skipped=0, failures=0, errors=0.
원문 출력은 최종 보고에 첨부.

## REQ 커버
REQ-01~17 전부 테스트 존재·통과(테스트 이름/@DisplayName에 REQ-xx 표기). 상세 표는 test-writer 보고 참조.

## 알려진 한계·미커버
- PG 연결 타임아웃(2s)은 읽기 타임아웃(504)·연결 거부(502)로 대체 검증.
- 결제 진행 중 sweep의 SKIP LOCKED 경합, 설계 §9 L-1(PG 승인 후 DB 커밋 직전 장애)은 미테스트.
- 쿠폰 UPDATE 0행의 reason은 항상 EXHAUSTED(설계 허용 범위).
- 헤더 누락 + 본문 오류 동시 시 `validation-failed`가 우선(둘 다 400).
- 미해결(BLOCKED) 경계면: 없음.
