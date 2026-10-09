# progress — order (주문 + 재고 차감)

확정 스택: Java 21 / Spring Boot 3.5.16 / Spring Data JPA + Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers / `./gradlew test` / skill: spring-boot
(Task 도구 미노출 세션 — 이 파일이 유일한 추적 기록)

## 요구사항
| ID | 이름 | passes | 근거 |
|---|---|---|---|
| R1 | 상품 등록 | ☑ | ProductApiTest$R1_CreateProduct (15) |
| R2 | 상품 조회 | ☑ | ProductApiTest$R2_GetProduct (3) |
| R3 | 주문 생성 | ☑ | OrderApiTest$R3_CreateOrder (15) |
| R4 | 주문 조회 | ☑ | OrderApiTest$R4_GetOrder (5) |
| R5 | 주문 취소 | ☑ | OrderApiTest$R5_CancelOrder (5) + 동시 취소 |
| R6 | 주문 목록 | ☑ | OrderApiTest$R6_ListOrders (14) |
| R7 | 동시성 | ☑ | OrderConcurrencyTest (4) |
| R8 | 에러 포맷 | ☑ | ProblemDetailsApiTest (21) |

## Phase 체크리스트
- [x] Phase 0 — 스택 확정(feature.md 명시 + build.gradle 일치), 00_requirements.json 작성
- [x] Phase 1 — 설계 (backend-designer) : 01_api_design.md, 02_db_design.md, 정합 요약 — 1회차 승인
- [x] Phase 2 — 구현·검증 : backend-impl 구현 + 스모크 14건 통과, boundary-verifier 4 경계면 1회차 PASS
- [x] Phase 3 — 테스트·통합 : test-writer 82건 추가(총 96), 독립 재실행 `./gradlew cleanTest test` 종료 코드 0 / 96 tests 0 failures 0 errors 0 skipped, 03_integration_summary.md 작성

## PM 검토 사이클
| Phase | 사이클 | 결과 |
|---|---|---|
| 1 | 1 | 승인. 원문 계약 일치, 정합 요약 2표 존재 |
| 2 | 1 | 승인. 설계 편차 없음, verifier 전 경계면 PASS |
| 3 | 1 | 승인. R1~R8 전부 전용 스위트, 독립 재실행으로 통과 확인 |

### Phase 1 PM 주석
- [NOTE.] 동시성: productId 오름차순 `FOR UPDATE` 단일 쿼리 + 판정 선행(쓰기 전 404/409). READ COMMITTED 유지 전제 — 구현에서 격리 수준 변경 금지.
- [NOTE.] 락 쿼리가 트랜잭션 내 Product/Order 첫 로드여야 함(1차 캐시). OrderItem.productId는 값 컬럼.
- [NOTE.] 원문 외 추가 규칙: name ≤ 255자(400). 0·음수 productId는 404. 원문과 충돌 없음 → 수용.
- [NOTE.] JPQL 엔티티명 `Order` 충돌 시 `@Entity(name="PurchaseOrder")` 폴백 — 구현에서 확인.
- [NOTE.] R7 테스트에 @Transactional 금지, Hikari 풀 10 대기 허용.

### Phase 2 PM 주석
- [NOTE.] backend-designer 워커에 SendMessage 도구 없음 → REDO 발생 시 PM이 중계하기로 함(실제 REDO 0건이라 미사용).
- [NOTE.] Order 엔티티명 충돌 없음, PurchaseOrder 폴백 미사용. 패키지 루트 com.example.order(진입점 OrderApplication).
- [NOTE.] verifier 커버리지 갭(Phase 3 전달): Accept: application/json → problem+json, Jackson 엄격 모드(1.5, "10"), 400>404 우선(productId 999 + quantity 0), path/query 타입 변환 400, 중복 productId 400, createdAt 동률 시 id desc. → Phase 3에서 전부 커버됨.

### Phase 3 PM 주석
- [NOTE.] 구현 수정 요청 0건(테스트가 구현 버그 미발견). 변이 검증은 수행하지 않음.
- [NOTE.] 테스트 미작성 영역: 500 internal-error, 계약 밖 프레임워크 오류(404 경로/405/415/406), name 255자 초과, 환경 변수 덮어쓰기. 원문 완료 조건 범위 밖.
- [NOTE.] 변경 사항 미커밋.

## verifier 판정 누적 (Phase 2)
| 회차 | 경계면 | 판정 | 비고 |
|---|---|---|---|
| 1 | 1 설계 ↔ 컨트롤러 | PASS | FIX/REDO 발행 없음 |
| 1 | 2 DB 설계 ↔ 엔티티·마이그레이션 | PASS | 〃 |
| 1 | 3 에러 계약 ↔ 예외 핸들러 | PASS | 〃 |
| 1 | 4 DTO nullable | PASS | 〃 |

REDO 카운터(verifier 소유): 전 경계면 0.

## 미해결(BLOCKED)
(없음)
