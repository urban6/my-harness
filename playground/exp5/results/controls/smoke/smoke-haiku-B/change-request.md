# 변경 요청: 포인트 혼합 결제와 부분 환불

## 배경
이 저장소는 `feature.md` 명세대로 동작하는 주문 결제 API다. 사용자 포인트를 도입해 포인트와 카드로 나눠 결제할 수 있게 하고, 결제된 주문을 항목 단위로 부분 환불할 수 있게 한다.

- 이 문서에 적지 않은 동작은 `feature.md`를 그대로 따른다. 기존 API·동작은 아래에서 바꾸는 것 외에는 그대로 유지한다.
- `feature.md` 범위의 "제외: 부분 취소"는 이 변경으로 해제된다.

## 바뀐 주문 상태

```
PENDING_PAYMENT ──결제 승인──▶ PAID ──배송──▶ SHIPPED ──배송 완료──▶ DELIVERED
                                │  ▲
                        부분 환불│  │
                                ▼  │ 배송
                       PARTIALLY_REFUNDED ──배송──▶ SHIPPED
                                │
                 마지막 항목 환불·취소│
                                ▼
                            REFUNDED   (PAID에서 취소·전량 환불해도 REFUNDED)
```

## 요구사항

### P1. 포인트 계정
- P1.1 `POST /api/points/{userId}/grants {amount}` → 200 `{userId, balance}`. 사용자의 포인트를 `amount`만큼 늘린다.
- P1.2 `userId`는 공백이 아닌 1~50자, `amount`는 1 ~ 10,000,000 정수. 위반 시 400.
- P1.3 `GET /api/points/{userId}` → 200 `{userId, balance}`. 적립한 적 없는 사용자도 404가 아니라 `balance` 0이다.
- P1.4 `balance`는 지금 쓸 수 있는 포인트다. 결제 대기·결제 완료 주문에 쓰인 포인트는 빠져 있다.

### P2. 주문에 포인트 사용
- P2.1 주문 생성(R3.1) 본문에 `usePoints`를 추가한다. 생략하면 0, 0 이상 정수가 아니면 400.
- P2.2 `usePoints`가 할인 후 `totalPrice`보다 크면 409(`POINTS_EXCEED_TOTAL`). 사용자의 `balance`보다 크면 409(`INSUFFICIENT_POINTS`).
- P2.3 오류 우선순위(C3)의 409 단계는 재고 → 쿠폰 → 포인트(`POINTS_EXCEED_TOTAL` → `INSUFFICIENT_POINTS`) 순이다.
- P2.4 생성 시 사용자의 `balance`가 `usePoints`만큼 줄어든다. 예약·쿠폰 사용과 함께 전부 반영되거나 전혀 반영되지 않아야 한다(R3.4).
- P2.5 주문 응답(R3.5)에 필드를 추가한다: `pointAmount`(= `usePoints`), `cardAmount`(= `totalPrice − pointAmount`), `refundedAmount`(환불된 총액, 처음 0). `items` 원소에는 `refundedQuantity`(환불된 수량, 처음 0)를 추가한다.
- P2.6 주문이 `CANCELLED`·`EXPIRED`·`PAYMENT_FAILED`가 되면 `pointAmount`가 사용자의 `balance`로 돌아간다(예약·쿠폰 복원과 같은 시점, R6.2의 2초 규칙 포함).
- P2.7 같은 `Idempotency-Key`로 `usePoints`만 다른 요청이 오면 다른 요청이다(R4.3, 422).

### P3. 결제 금액
- P3.1 결제(R5) 시 PG에 요청하는 `amount`는 `cardAmount`다.
- P3.2 `cardAmount`가 0이면 PG를 호출하지 않고 바로 승인 처리한다(R5.7을 대체).
- P3.3 PG 거절(R5.5)이면 주문은 `PAYMENT_FAILED`가 되고 P2.6에 따라 포인트도 복원된다. PG 장애(R5.6)면 포인트도 바뀌지 않는다.

### P4. 부분 환불
- P4.1 `POST /api/orders/{id}/refunds` 헤더 `Idempotency-Key`(1~64자), 본문 `{items:[{productId, quantity}]}` → 200, 본문은 주문(R3.5 + P2.5) 형태.
- P4.2 `items` 1~20개, `quantity` 1 이상, 같은 `productId` 중복 불가, 헤더 누락 포함 위반 시 400. 주문이 없으면 404.
- P4.3 주문이 `PAID` 또는 `PARTIALLY_REFUNDED`가 아니면 409(`INVALID_STATE`).
- P4.4 주문에 없는 상품이거나, 항목의 남은 수량(`quantity − refundedQuantity`)보다 많이 요청하면 409(`REFUND_QUANTITY_EXCEEDED`). 이 검사는 P4.3 다음이다.
- P4.5 환불액은 **누적 기준으로 안분**한다. 환불된 정가 합 `G = Σ(unitPrice × refundedQuantity)`일 때 누적 환불액은 `floor(totalPrice × G / subtotal)`이다. 이번 환불액 = (이번 환불 반영 후 누적 환불액) − (반영 전 누적 환불액). 따라서 전 항목을 환불하면 누적 환불액은 정확히 `totalPrice`다.
- P4.6 이번 환불액은 **카드 먼저** 돌려준다. 카드 환불액 = `min(이번 환불액, cardAmount − 지금까지 카드로 환불한 금액)`, 나머지는 포인트로 사용자의 `balance`에 돌려준다.
- P4.7 카드 환불액이 0보다 크면 PG에 환불을 요청한다(아래 PG 계약). PG 요청의 `Idempotency-Key`에는 클라이언트가 보낸 `Idempotency-Key`를 그대로 쓴다. 카드 환불액이 0이면 PG를 호출하지 않는다.
- P4.8 PG가 5xx를 주거나, 연결에 실패하거나, 2초 안에 응답하지 않으면 503(`PAYMENT_GATEWAY_UNAVAILABLE`). 주문·재고·쿠폰·포인트는 바뀌지 않는다.
- P4.9 성공하면 각 항목의 `refundedQuantity`가 늘고, 해당 상품의 `stock`이 환불 수량만큼 늘고, 주문의 `refundedAmount`가 이번 환불액만큼 는다. 모든 항목이 전량 환불되면 `REFUNDED`가 되고 쿠폰 사용이 복원된다(R2.6). 아니면 `PARTIALLY_REFUNDED`.
- P4.10 멱등성은 R4.2~R4.5와 같다. 키 공간은 주문 생성·결제와 서로 독립이다.

### P5. 기존 동작 변경
- P5.1 취소(R7): `PAID`·`PARTIALLY_REFUNDED` 주문을 취소하면 남은 전 항목을 P4 규칙으로 환불하고 `REFUNDED`가 된다. 이때 PG 환불 요청의 `Idempotency-Key`는 `cancel-{orderId}`다. PG 장애면 503이고 아무것도 바뀌지 않는다.
- P5.2 배송(R8): `PARTIALLY_REFUNDED` 주문도 `ship`으로 `SHIPPED`가 된다. `SHIPPED`·`DELIVERED` 주문은 환불·취소할 수 없다(409 `INVALID_STATE`).
- P5.3 주문 목록(R9)의 `status` 필터에 `PARTIALLY_REFUNDED`를 쓸 수 있다.
- P5.4 새 오류도 R11 형식(Problem Details + `code`)이다. 409 `code`에 `POINTS_EXCEED_TOTAL` · `INSUFFICIENT_POINTS` · `REFUND_QUANTITY_EXCEEDED`를 추가한다.

### P6. 동시성
- P6.1 `balance`가 1,000인 사용자가 `usePoints` 600 주문 5건을 동시에 요청하면(키는 서로 다름) 정확히 1건 201, 최종 `balance` 400.
- P6.2 남은 수량이 3인 항목에 수량 1 부분 환불 5건을 동시에 요청하면(키는 서로 다름) 정확히 3건 200, 2건 409(`REFUND_QUANTITY_EXCEEDED`). 최종 `refundedQuantity` 3, PG 환불 금액 합은 그 주문의 카드 환불 누적액과 같다.
- P6.3 같은 주문에 취소와 부분 환불이 동시에 와도 5xx 없이 처리되고, 최종적으로 환불 수량·`refundedAmount`·재고·PG 환불 금액 합이 서로 맞는다.

## 바뀐 외부 PG 계약
`feature.md`의 PG 계약에서 환불만 바뀐다.

| 요청 | 응답 |
|---|---|
| `POST /v1/payments/{paymentId}/refund` 헤더 `Idempotency-Key`, 본문 `{amount}` | 200 `{paymentId, status: "REFUNDED", amount}` |

- 같은 `Idempotency-Key`의 환불 요청은 다시 환불하지 않고 최초 결과를 돌려준다.
- 누적 환불 금액이 결제 금액을 넘는 요청은 400이다.
- 5xx 응답, 연결 실패, 2초 초과는 PG 장애다.

## 완료 조건
- ./gradlew test 통과 (기존 테스트 포함)
- P1~P6 각각을 검증하는 테스트가 있을 것
