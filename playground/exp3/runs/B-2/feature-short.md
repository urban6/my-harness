# 기능: 주문 결제 (쿠폰 · 재고 예약 · 외부 PG)

상품·쿠폰을 등록하고, 사용자가 주문하면 재고를 예약한 뒤 외부 PG로 결제·환불하고 배송 상태를 관리하는 백엔드 API를 만들어 줘.

## 스택
- Java 21, Spring Boot 3.x, JPA, PostgreSQL, Flyway
- 테스트: JUnit 5 + Testcontainers(PostgreSQL), `./gradlew test`
- 환경 변수: `SPRING_DATASOURCE_URL`·`_USERNAME`·`_PASSWORD`, `SERVER_PORT`(기본 8080), `PAYMENT_GATEWAY_URL`, `ORDER_PAYMENT_TTL`(ISO-8601 기간, 기본 `PT15M`)

## API
사용자는 `X-User-Id` 헤더로만 식별한다(인증 없음).

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/products` | `{name, price, stock}` | 201 + `Location`, 상품 |
| GET | `/api/products/{id}` | | 상품 `{id, name, price, stock, reserved, available}` |
| POST | `/api/coupons` | `{code, type(FIXED·RATE), value, minOrderAmount, maxDiscountAmount, totalQuantity, validFrom, validUntil}` | 201 + `Location`, 쿠폰 |
| GET | `/api/coupons/{code}` | | 쿠폰 (요청 필드 + `usedCount`) |
| POST | `/api/orders` | 헤더 `X-User-Id`, `Idempotency-Key` · 본문 `{items:[{productId, quantity}], couponCode?}` | 201 + `Location`, 주문 |
| GET | `/api/orders/{id}` | | 주문 |
| GET | `/api/orders?userId&status&size&cursor` | | `{content[], nextCursor}` |
| POST | `/api/orders/{id}/pay` | 헤더 `Idempotency-Key` · 본문 `{cardToken}` | 주문 |
| POST | `/api/orders/{id}/cancel` | | 주문 |
| POST | `/api/orders/{id}/ship` · `/deliver` | | 주문 |

- 주문: `{id, userId, status, items[{productId, quantity, unitPrice}], couponCode, subtotal, discount, totalPrice, createdAt, expiresAt, paidAt}`
- 주문 상태: `PENDING_PAYMENT`, `PAID`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`, `SHIPPED`, `DELIVERED`
- 결제 대기 주문은 `ORDER_PAYMENT_TTL`이 지나면 만료된다.
- 오류 응답은 RFC 9457 Problem Details.

## 외부 PG
`PAYMENT_GATEWAY_URL` 기준.
- `POST /v1/payments` 헤더 `Idempotency-Key`, 본문 `{orderId, amount, cardToken}` → 200 `{paymentId, status: APPROVED | DECLINED}`
- `POST /v1/payments/{paymentId}/refund` → 200 `{paymentId, status: "REFUNDED"}`
