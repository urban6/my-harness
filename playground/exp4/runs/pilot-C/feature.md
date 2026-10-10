# 기능: 주문 결제 (쿠폰 · 재고 예약 · 외부 PG)

## 배경
상품·쿠폰을 등록하고, 사용자가 주문하면 재고를 예약한 뒤 외부 결제 대행사(PG)로 결제·환불·배송까지 처리하는 백엔드 API가 필요하다.

## 스택
- Java 21, Spring Boot 3.x, JPA, PostgreSQL, Flyway
- 테스트: JUnit 5 + Testcontainers(PostgreSQL)
- 테스트 명령: ./gradlew test

## 범위
- 포함: 상품 등록·조회, 쿠폰 등록·조회, 주문 생성·조회·목록, 결제, 결제 만료, 취소·환불, 배송 상태
- 제외: 인증·인가(사용자는 `X-User-Id` 헤더로만 식별), 상품·쿠폰 수정·삭제, 부분 취소, 멱등 키 만료

## 공통 규약
- C1. 금액(`price`, `unitPrice`, `subtotal`, `discount`, `totalPrice`, `amount` 등)은 정수(원 단위)이며 `int` 범위를 넘을 수 있다.
- C2. 시각(`createdAt`, `expiresAt`, `paidAt`, `validFrom`, `validUntil`)은 오프셋이 포함된 ISO-8601 문자열이다.
- C3. 한 요청에 오류가 여럿이면 **400 → 멱등 키(422·409) → 404 → 409 → PG 결과(402·503)** 순으로 먼저 해당하는 것을 반환한다. 같은 단계 안의 409는 재고 → 쿠폰 순이다.
- C4. 런타임 설정은 환경 변수로 덮어쓸 수 있어야 한다.

  | 환경 변수 | 의미 | 기본값 |
  |---|---|---|
  | `SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` | DB 접속 | 임의 |
  | `SERVER_PORT` | 서버 포트 | 8080 |
  | `PAYMENT_GATEWAY_URL` | PG 기본 URL (예: `http://localhost:9090`) | 임의 |
  | `ORDER_PAYMENT_TTL` | 결제 대기 만료 시간, ISO-8601 기간 (예: `PT15M`, `PT3S`) | `PT15M` |

## 주문 상태

```
PENDING_PAYMENT ──결제 승인──▶ PAID ──배송──▶ SHIPPED ──배송 완료──▶ DELIVERED
      │                          │
      ├──결제 거절──▶ PAYMENT_FAILED
      ├──만료──────▶ EXPIRED      └──취소(환불)──▶ REFUNDED
      └──취소──────▶ CANCELLED
```

## 요구사항

### R1. 상품
- R1.1 `POST /api/products {name, price, stock}` → 201 + `Location`, 본문은 R1.3과 같은 형태(`reserved`=0).
- R1.2 `name` 필수(공백만 불가, 100자 이하), `price` 1 ~ 10,000,000, `stock` 0 ~ 1,000,000. 위반 시 400.
- R1.3 `GET /api/products/{id}` → 200 `{id, name, price, stock, reserved, available}`. 없으면 404.
- R1.4 `stock`은 판매되지 않은 보유 수량, `reserved`는 결제 대기 주문이 잡아 둔 수량, `available = stock − reserved`.

### R2. 쿠폰
- R2.1 `POST /api/coupons {code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity, validFrom, validUntil}` → 201 + `Location`, 본문은 R2.3과 같은 형태(`usedCount`=0).
- R2.2 `code`는 영문 대문자·숫자 4~20자, `type`은 `FIXED`(정액) 또는 `RATE`(정률). `value`는 FIXED면 1 이상, RATE면 1~100(%). `minOrderAmount` 0 이상(생략 시 0), `maxDiscountAmount` 1 이상 또는 생략(제한 없음), `totalQuantity` 1 이상, `validFrom < validUntil`. 위반 시 400. 이미 있는 `code`면 409.
- R2.3 `GET /api/coupons/{code}` → 200 `{code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity, usedCount, validFrom, validUntil}`. 없으면 404.
- R2.4 할인액: `subtotal = Σ(unitPrice × quantity)`. FIXED는 `value`, RATE는 `floor(subtotal × value / 100)`. 이어서 `maxDiscountAmount`로 상한, 마지막으로 `subtotal`로 상한. `totalPrice = subtotal − discount`.
- R2.5 쿠폰을 쓸 수 있는 조건: 요청 시각이 `validFrom` 이상 `validUntil` 미만, `subtotal ≥ minOrderAmount`, 같은 사용자가 이 쿠폰을 사용 중인 주문이 없음. 하나라도 어기면 409(`COUPON_NOT_APPLICABLE`). `usedCount = totalQuantity`면 409(`COUPON_EXHAUSTED`).
- R2.6 `usedCount`는 이 쿠폰을 사용 중인 주문 수다. 주문이 `CANCELLED`·`EXPIRED`·`PAYMENT_FAILED`·`REFUNDED`가 되면 사용이 복원된다(`usedCount` 1 감소, 같은 사용자가 다시 사용 가능).

### R3. 주문 생성·조회
- R3.1 `POST /api/orders` 헤더 `X-User-Id`(공백 아닌 1~50자), `Idempotency-Key`(1~64자), 본문 `{items:[{productId, quantity}], couponCode}` → 201 + `Location`, 본문은 R3.5와 같은 형태(`status`=`PENDING_PAYMENT`).
- R3.2 `items` 1~20개, `quantity` 1~1,000, 같은 `productId` 중복 불가, `couponCode`는 생략 가능. 헤더 누락·위반 포함 400.
- R3.3 없는 상품·쿠폰은 404. 어떤 항목이든 `available`이 부족하면 409(`INSUFFICIENT_STOCK`).
- R3.4 생성 시 각 상품의 `reserved`가 주문 수량만큼 늘고, 쿠폰 `usedCount`가 1 늘어난다. 모든 검사를 통과할 때만 생성하며, 예약·쿠폰 사용은 전부 반영되거나 전혀 반영되지 않아야 한다.
- R3.5 `GET /api/orders/{id}` → 200 `{id, userId, status, items[{productId, quantity, unitPrice}], couponCode, subtotal, discount, totalPrice, createdAt, expiresAt, paidAt}`. `unitPrice`는 주문 시점 상품 가격, `couponCode`·`paidAt`은 없으면 null, `expiresAt = createdAt + ORDER_PAYMENT_TTL`. 없으면 404.

### R4. 멱등성 (주문 생성 · 결제)
- R4.1 주문 생성(R3.1)과 결제(R5.1)는 `Idempotency-Key` 헤더가 필수다. 키 공간은 두 엔드포인트가 서로 독립이다.
- R4.2 같은 키로 같은 요청(같은 `X-User-Id`·경로·본문)이 다시 오면, 처리하지 않고 최초 응답과 같은 상태코드·본문을 돌려준다.
- R4.3 같은 키로 다른 요청이 오면 422(`IDEMPOTENCY_KEY_MISMATCH`).
- R4.4 2xx 응답만 저장된다. 오류로 끝난 요청의 키는 같은 요청으로 다시 시도할 수 있다.
- R4.5 같은 키의 요청이 동시에 오면 실제 처리는 한 번만 일어난다. 나머지는 최초 응답의 재생이거나 409(`IDEMPOTENCY_IN_PROGRESS`)다.

### R5. 결제
- R5.1 `POST /api/orders/{id}/pay` 헤더 `Idempotency-Key`, 본문 `{cardToken}`(공백 불가) → 결과에 따라 아래 응답. 본문은 R3.5와 같은 형태.
- R5.2 주문이 `PENDING_PAYMENT`가 아니거나 `expiresAt`이 지났으면 409(`INVALID_STATE`). 없으면 404.
- R5.3 PG에 결제를 요청한다(아래 PG 계약). `cardToken`은 그대로 전달하고, PG 요청의 `Idempotency-Key`에는 클라이언트가 보낸 `Idempotency-Key`를 그대로 쓴다.
- R5.4 승인 → 200, `status`=`PAID`, `paidAt` 기록. 각 상품의 `stock`과 `reserved`가 주문 수량만큼 줄어든다.
- R5.5 거절 → 402(`PAYMENT_DECLINED`). 주문은 `PAYMENT_FAILED`가 되고 예약·쿠폰 사용이 복원된다.
- R5.6 PG가 5xx를 주거나, 연결에 실패하거나, **2초** 안에 응답하지 않으면 503(`PAYMENT_GATEWAY_UNAVAILABLE`). 주문·재고·쿠폰은 바뀌지 않는다.
- R5.7 `totalPrice`가 0이면 PG를 호출하지 않고 바로 R5.4로 처리한다.

### R6. 결제 만료
- R6.1 `PENDING_PAYMENT` 주문은 `expiresAt`이 지나면 `EXPIRED`가 되고 예약·쿠폰 사용이 복원된다.
- R6.2 이 변화는 `expiresAt` 후 **2초** 안에 주문·상품·쿠폰 조회 결과에 반영되어야 한다.

### R7. 취소·환불
- R7.1 `POST /api/orders/{id}/cancel` → 200, 본문은 R3.5와 같은 형태. 없으면 404.
- R7.2 `PENDING_PAYMENT` → `CANCELLED`, 예약·쿠폰 사용 복원.
- R7.3 `PAID` → PG에 환불을 요청한다. 성공하면 `REFUNDED`, 각 상품의 `stock`이 주문 수량만큼 늘고 쿠폰 사용이 복원된다. PG가 5xx·연결 실패·2초 초과면 503, 주문·재고·쿠폰은 바뀌지 않는다.
- R7.4 그 밖의 상태는 409(`INVALID_STATE`).

### R8. 배송
- R8.1 `POST /api/orders/{id}/ship`: `PAID` → `SHIPPED`. `POST /api/orders/{id}/deliver`: `SHIPPED` → `DELIVERED`. 둘 다 200, 본문은 R3.5와 같은 형태.
- R8.2 그 밖의 상태에서 호출하면 409(`INVALID_STATE`). 없으면 404.

### R9. 주문 목록
- R9.1 `GET /api/orders?userId&status&size&cursor` → 200 `{content[], nextCursor}`. `content` 원소는 R3.5와 같은 형태, 다음 페이지가 없으면 `nextCursor`는 null.
- R9.2 `userId`·`status`는 선택 필터(둘 다 주면 AND). `status`가 정의되지 않은 값이면 400.
- R9.3 `size` 기본 20, 1~100. 범위 밖이면 400. 해석할 수 없는 `cursor`도 400.
- R9.4 정렬은 `createdAt` 내림차순, 같으면 `id` 내림차순.
- R9.5 페이지를 넘기는 사이 새 주문이 생겨도, 첫 페이지 시점에 있던 주문은 이어지는 페이지들에서 중복·누락 없이 정확히 한 번씩 나온다.

### R10. 동시성
- R10.1 `available`이 10인 상품에 수량 1 주문 20건을 동시에 요청하면 정확히 10건 201, 10건 409, 최종 `reserved` 10.
- R10.2 `totalQuantity` 5인 쿠폰을 서로 다른 사용자 15명이 동시에 사용하면 정확히 5건 201, 10건 409, 최종 `usedCount` 5.
- R10.3 한 사용자가 같은 쿠폰으로 주문 5건을 동시에 요청하면(키는 서로 다름) 정확히 1건 201.
- R10.4 상품 P·Q에 대해 `[P, Q]` 순서 주문과 `[Q, P]` 순서 주문을 섞어 동시에 요청해도 5xx 없이 모두 처리되고 `reserved`가 정확하다.
- R10.5 같은 주문에 결제 요청이 동시에 와도(키는 서로 다름) PG 결제 요청은 최대 1번이고, 성공 응답은 1건이다.

### R11. 에러 포맷
- R11.1 R1~R10에서 정의한 모든 오류 응답과 요청 본문 JSON 파싱 실패(400)는 RFC 9457 Problem Details다. `Content-Type: application/problem+json`.
- R11.2 필드는 최소 `type`, `title`, `status`, `detail`, `code`.
- R11.3 `code`는 아래 값 중 하나다.

  | 상태 | code |
  |---|---|
  | 400 | `VALIDATION_ERROR` (본문·헤더·쿼리 검증, JSON 파싱 실패 포함) |
  | 402 | `PAYMENT_DECLINED` |
  | 404 | `PRODUCT_NOT_FOUND` · `COUPON_NOT_FOUND` · `ORDER_NOT_FOUND` |
  | 409 | `INSUFFICIENT_STOCK` · `COUPON_NOT_APPLICABLE` · `COUPON_EXHAUSTED` · `DUPLICATE_COUPON_CODE` · `INVALID_STATE` · `IDEMPOTENCY_IN_PROGRESS` |
  | 422 | `IDEMPOTENCY_KEY_MISMATCH` |
  | 503 | `PAYMENT_GATEWAY_UNAVAILABLE` |

## 외부 PG 계약
`PAYMENT_GATEWAY_URL`을 기준으로 한다. PG는 같은 `Idempotency-Key`의 결제 요청에 대해 다시 결제하지 않고 최초 결과를 돌려준다.

| 요청 | 응답 |
|---|---|
| `POST /v1/payments` 헤더 `Idempotency-Key`, 본문 `{orderId, amount, cardToken}` | 200 `{paymentId, status}` — `status`는 `APPROVED` 또는 `DECLINED` |
| `POST /v1/payments/{paymentId}/refund` | 200 `{paymentId, status: "REFUNDED"}` |

- 5xx 응답, 연결 실패, 2초 초과는 PG 장애다(R5.6, R7.3).

## 완료 조건
- ./gradlew test 통과
- R1~R11 각각을 검증하는 테스트가 있을 것
