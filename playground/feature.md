# 기능: 주문(Order) + 재고 차감

## 배경
상품을 등록하고, 재고 범위 안에서 주문·취소할 수 있는 백엔드 API가 필요하다.

## 스택
- Java 21, Spring Boot 3.x, JPA, PostgreSQL, Flyway
- 테스트: JUnit 5 + Testcontainers(PostgreSQL)
- 테스트 명령: ./gradlew test

## 범위
- 포함: 상품 등록·조회, 주문 생성·조회·취소·목록
- 제외: 인증·인가, 결제, 상품 수정·삭제

## 공통 규약
- 금액(`price`, `unitPrice`, `totalPrice`)은 정수(원 단위)다.
- 시각(`createdAt`)은 ISO-8601 문자열이다.
- 주문 상태는 `ORDERED`(생성 직후)와 `CANCELLED`(취소 후) 두 가지다.
- 한 요청에 오류가 여럿이면 400 → 404 → 409 순으로 먼저 해당하는 것을 반환한다.

## 요구사항
| ID | 기능 | 계약 | 규칙 |
|---|---|---|---|
| R1 | 상품 등록 | POST /api/products {name, price, stock} → 201 + Location, 본문은 R2 응답과 같은 형태 | name 필수(공백만 있는 문자열 불가), price > 0, stock ≥ 0. 위반 시 400 |
| R2 | 상품 조회 | GET /api/products/{id} → 200 {id, name, price, stock} | 없으면 404 |
| R3 | 주문 생성 | POST /api/orders {items:[{productId, quantity}]} → 201 + Location, 본문은 R4 응답과 같은 형태(status=ORDERED) | items 1개 이상, quantity ≥ 1, 같은 productId 중복 불가 — 위반 시 400. 없는 상품 404, 재고 부족 409. 모든 항목의 재고가 충분할 때만 생성하고, 재고는 전부 차감되거나 전혀 차감되지 않아야 한다 |
| R4 | 주문 조회 | GET /api/orders/{id} → 200 {id, status, totalPrice, items[{productId, quantity, unitPrice}], createdAt} | unitPrice는 주문 시점의 상품 가격, totalPrice = Σ(unitPrice × quantity). 없으면 404 |
| R5 | 주문 취소 | POST /api/orders/{id}/cancel → 200, 본문은 R4 응답과 같은 형태 | status=CANCELLED, 주문 수량만큼 재고 복원. 이미 취소된 주문 409, 없으면 404 |
| R6 | 주문 목록 | GET /api/orders?page&size → 200 {content[], page, size, totalElements} | content 원소는 R4 응답과 같은 형태. page 기본 0(≥ 0), size 기본 20(1~100), 범위 밖이면 400. createdAt 내림차순, 같으면 id 내림차순 |
| R7 | 동시성 | — | 재고 10개인 상품에 수량 1 주문 20건을 동시에 요청하면 정확히 10건 성공(201), 나머지 10건은 409, 최종 재고 0 |
| R8 | 에러 포맷 | R1~R7에서 정의한 모든 에러 응답 + 요청 본문 JSON 파싱 실패(400) | RFC 9457 Problem Details, Content-Type: application/problem+json, 최소 필드 type·title·status·detail |

## 완료 조건
- ./gradlew test 통과
- R1~R8 각각을 검증하는 테스트가 있을 것
