# progress — order (주문 + 재고 차감)

> 추적: Task 도구 미가용 세션 → 이 파일이 단일 기록.

## 확정 스택
Java 21 / Spring Boot 3.x(build.gradle: 3.5.16) / JPA + Hibernate, PostgreSQL / Flyway / JUnit 5 + Testcontainers(PostgreSQL) / `./gradlew test` / skill: spring-boot

## 요구사항
| ID | 이름 | 상태 | 테스트 |
|---|---|---|---|
| R1 | 상품 등록 | completed | ProductApiTest |
| R2 | 상품 조회 | completed | ProductApiTest |
| R3 | 주문 생성 | completed | OrderCreateApiTest |
| R4 | 주문 조회 | completed | OrderRetrieveCancelApiTest |
| R5 | 주문 취소 | completed | OrderRetrieveCancelApiTest |
| R6 | 주문 목록 | completed | OrderListApiTest |
| R7 | 동시성 | completed | OrderConcurrencyTest |
| R8 | 에러 포맷 | completed | ErrorFormatTest |

## Phase 체크리스트
- [x] Phase 0 — 스택 확정, 00_requirements.json 작성
- [x] Phase 1 — 설계 (backend-designer) : 01_api_design.md, 02_db_design.md, 정합 요약 — 사이클 1/3, 통과
- [x] Phase 2 — 구현·검증 — 4 경계면 최종 PASS, REDO 0, BLOCKED 0
- [x] Phase 3 — 테스트·통합 (test-writer) — 79 tests 통과, 03_integration_summary.md 작성

## PM 검토 주석
- [NOTE.] Phase 0: 매니페스트 판별 시 build.gradle(Groovy)을 놓쳐 "greenfield"로 기록했으나, 실제로는 build.gradle(Boot 3.5.16)·gradlew·OrderApplication(com.example.order)이 있음. 스택은 feature.md 명시값과 일치하므로 영향 없음.
- [NOTE.] Phase 1: 정합 요약 9(a) 필드↔컬럼, 9(b) 제약↔상태코드 매핑 누락 없음. 동시성은 조건부 원자 UPDATE + productId 오름차순으로 R7 충족 가능.
- [NOTE.] Phase 1: amount-overflow(400)가 404 이후 판정되는 유일한 우선순위 예외 — 요구사항 밖 극단 케이스이므로 수용.
- [NOTE.] Phase 1: [Q.] 기본안 모두 요구사항과 충돌하지 않아 채택.
- [NOTE.] Phase 2: backend-designer 세션에 SendMessage 도구 없음 → REDO 발생 시 PM 중계 필요했음(실제 REDO 없음).
- [NOTE.] Phase 2: verifier는 테스트를 직접 실행하지 않음 → Phase 3 test-writer 실행 결과로 최종 확인.
- [NOTE.] Phase 3: test-writer가 기존 스모크·support 클래스를 유지하고 헬퍼만 추가(재작성 없음). build.gradle에 testLogging 추가. 구현 버그 발견 없음. R7 단독 3회 추가 실행 모두 통과.
- [NOTE.] Phase 3: PM 세션에는 Bash가 없어 테스트를 직접 재실행하지 못함. 보고한 결과는 test-writer가 실행한 콘솔 출력 원문.

## verifier 판정 누적
| 회차 | 경계면 | 판정 | 비고 |
|---|---|---|---|
| 1 | 1. 설계 ↔ 컨트롤러 | FIX | R6 page×size > Integer.MAX_VALUE 시 500 → OrderService.list 가드 추가 |
| 1 | 2. DB 설계 ↔ 엔티티·마이그레이션 | PASS | |
| 1 | 3. 에러 계약 ↔ 예외 핸들러 | PASS | |
| 1 | 4. DTO nullable | PASS | |
| 2 | 1. 설계 ↔ 컨트롤러 | PASS | OrderService.java:106-108 |

REDO 카운트(verifier 소유): 전 경계면 0

## 미해결(BLOCKED)
(없음)
