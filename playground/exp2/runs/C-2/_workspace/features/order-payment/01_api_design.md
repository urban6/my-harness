# 01. API 설계 — order-payment

- 요구 원문(단일 진실 원천): `feature.md` / 요구 분해: `00_requirements.json`
- 스택: Java 21 · Spring Boot 3.5.x · Spring MVC · JPA(Hibernate 6) · PostgreSQL · Flyway · JUnit 5 + Testcontainers
- 데이터 모델: [`02_db_design.md`](./02_db_design.md) — 이 문서 끝 **§16 정합 요약**이 API↔DB 정렬의 유일한 기준표다.
- 표기: `[DECISION]` = 요구가 열어 둔 부분을 설계에서 확정한 것(근거 포함). `[LIMIT]` = 범위 밖으로 남긴 알려진 한계.

---

## 0. 패키지 구조 (확정)

진입점 `com.example.order.OrderApplication`이 이미 존재하므로 루트 패키지는 **`com.example.order`** 로 확정한다(컴포넌트 스캔 범위 유지). 스킬 기본값인 기능별(by-feature) 구조를 따른다. 주문 기능 패키지는 `com.example.order.order` 중복을 피하려고 `ordering`으로 한다. `[DECISION]`

```
com.example.order
├── OrderApplication.java            # @SpringBootApplication @EnableScheduling @ConfigurationPropertiesScan
├── common/
│   ├── error/    ErrorCode, BusinessException(+하위 예외), GlobalExceptionHandler
│   ├── config/   JacksonConfig, ClockConfig(Clock.systemUTC() 빈), OrderProperties, PaymentGatewayProperties
│   └── web/      TimeFormats(Instant→OffsetDateTime UTC 변환), RequestHeaders 검증 유틸
├── product/      ProductController, ProductService, ProductRepository, Product, dto/
├── coupon/       CouponController, CouponService, CouponRepository, Coupon, CouponType, dto/
├── ordering/     OrderController, OrderService(+OrderPaymentService), OrderRepository, Order, OrderLine,
│                 OrderStatus, OrderExpirationScheduler, OrderCursor, dto/
├── payment/      PaymentGatewayClient, PaymentResult, PaymentGatewayUnavailableException
└── idempotency/  IdempotencyService, IdempotencyRepository(JdbcTemplate), IdempotencyScope, RequestFingerprint
```

레이어: `web(Controller) → service → repository`. 트랜잭션은 서비스(또는 `TransactionTemplate`)에서만. 엔티티는 응답에 노출하지 않고 record DTO로 변환한다.

---

## 1. 공통 규약

### 1.1 기본
- Base path `/api`. 요청·성공 응답 `Content-Type: application/json`(UTF-8). 오류 응답은 `application/problem+json`(§11).
- **null 필드는 생략하지 않고 `null`로 직렬화한다**(`couponCode`, `paidAt`, `maxDiscountAmount`, `nextCursor`). `spring.jackson.default-property-inclusion`을 `non_null`로 바꾸지 말 것.
- 알 수 없는 JSON 필드는 무시한다(Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false` 유지). `[DECISION]` 요구가 거부를 요구하지 않으며, 멱등 지문을 "의미 기준"으로 계산하는 §6.3과 일관된다.

### 1.2 시각 (C2)
- 응답의 모든 시각은 **UTC 오프셋 `Z`가 붙은 ISO-8601** 문자열이다. 예: `"2026-10-10T03:15:00.123456Z"`. DTO 타입은 `OffsetDateTime`이고 `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)`로 만든다.
- 정밀도는 **마이크로초**다. 서버가 만드는 시각(`createdAt`, `expiresAt`, `paidAt`)은 생성 시점에 `Instant.truncatedTo(ChronoUnit.MICROS)`로 자른 뒤 저장한다. 이렇게 해야 201 응답 값 = DB 값 = 이후 GET 값 = 커서 값이 된다(PostgreSQL TIMESTAMPTZ는 µs 정밀도).
- 요청의 시각(`validFrom`, `validUntil`)은 **오프셋이 반드시 있는** ISO-8601 문자열이다(`DateTimeFormatter.ISO_OFFSET_DATE_TIME`. `Z`와 `+09:00` 모두 허용). 오프셋이 없거나 해석할 수 없거나 문자열이 아니면 400이다. DTO에서는 `String`으로 받아 서비스 진입 전에 파싱한다(필드 단위 오류 메시지를 주려는 목적).
- `[DECISION]` 저장은 TIMESTAMPTZ(시점)이므로 입력 오프셋은 보존되지 않는다. 응답은 같은 **시점**을 UTC로 정규화해 돌려준다(예: 입력 `2026-01-01T00:00:00+09:00` → 응답 `2025-12-31T15:00:00Z`). 테스트는 문자열이 아니라 `Instant`(`OffsetDateTime.parse(..).toInstant()`)로 비교해야 한다.
- 시간 판단(쿠폰 유효기간, 만료)은 주입된 `java.time.Clock`(UTC) 하나로 한다. **DB의 `now()`를 쓰지 않는다** — 컨테이너 시계와 앱 시계의 차이가 R6.2의 2초 창을 흔들 수 있기 때문이다.

### 1.3 금액·수치 (C1)
- 금액(`price`, `unitPrice`, `subtotal`, `discount`, `totalPrice`, `value`, `minOrderAmount`, `maxDiscountAmount`, PG `amount`)은 Java `long`, DB `BIGINT`, JSON 정수다. 곱셈은 `Math.multiplyExact`를 쓴다(이론상 최대값 `10^7 × 1000 × 20 = 2×10^11`, RATE 계산 중간값 `2×10^13` — `long` 범위 안).
- 수량·재고(`stock`, `reserved`, `available`, `quantity`)는 `int`/`INTEGER`다(상한 1,000,000).
- 요청 DTO의 수치 필드는 박싱 타입(`Long`/`Integer`)으로 받아 `@NotNull` 검증을 하고, 범위는 Bean Validation으로 검사한다.

### 1.4 JSON 역직렬화 엄격성 (타입 불일치 → 400)
아래는 모두 `HttpMessageNotReadableException`이 되고 → **400 `VALIDATION_ERROR`** 로 매핑된다.
- JSON 문법 오류, 빈 본문, 본문 `null`, 끝에 남는 토큰(`{}garbage`)
- 숫자 필드에 소수(`1.5`, `1.0`) / 문자열(`"10"`) / 불리언, 정수 오버플로
- 문자열 필드에 숫자·불리언(`"name": 123`)
- 배열 자리에 객체(`"items": {}`)

설정(구현 지침):
```yaml
spring.jackson:
  deserialization:
    accept-float-as-int: false
    fail-on-trailing-tokens: true
    fail-on-null-for-primitives: true
  mapper:
    allow-coercion-of-scalars: false      # Integer/Long/Boolean ← 문자열 강제변환 금지
  serialization:
    write-dates-as-timestamps: false
```
그리고 `Jackson2ObjectMapperBuilderCustomizer`(`postConfigurer`)에서 `coercionConfigFor(LogicalType.Textual)`의 `Integer`/`Float`/`Boolean` 입력 모양을 `CoercionAction.Fail`로 설정한다(문자열 필드에 숫자 금지 — `allow-coercion-of-scalars`만으로는 막히지 않는다).

### 1.5 헤더
| 헤더 | 사용처 | 규칙 | 위반 |
|---|---|---|---|
| `X-User-Id` | `POST /api/orders` **필수** | 공백만으로 된 문자열 불가(`String.isBlank()==false`), 길이 1~50 (`String.length()`) | 누락·위반 400 |
| `X-User-Id` | `POST /api/orders/{id}/pay` 선택 | 검증하지 않음. 오직 멱등 지문(§6.3)에만 들어감 | — |
| `Idempotency-Key` | `POST /api/orders`, `POST /api/orders/{id}/pay` **필수** | 공백만으로 된 문자열 불가, 길이 1~64 | 누락·위반 400 |

- `[DECISION]` "공백 아닌 1~50자"는 **공백만으로 된 값 금지 + 1~50자**로 해석한다(R1.2의 "공백만 불가"와 같은 의미). 값은 trim하지 않고 그대로 저장·비교한다(HTTP 파서가 앞뒤 OWS를 제거하는 것은 별개).
- 헤더는 `@RequestHeader(required = false) String`으로 받아 서비스 진입 전에 직접 검증한다(누락 오류와 형식 오류를 같은 400 경로로 모으고, `errors[]`에 `header:X-User-Id` 식으로 보고). `MissingRequestHeaderException`/`HandlerMethodValidationException`이 나도 400으로 매핑되므로 어느 방식으로 구현해도 계약은 같다.

### 1.6 Location
- 201 응답의 `Location`은 절대 URI다: `ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/products/{id}")…`.
  - 상품 `/api/products/{id}`, 쿠폰 `/api/coupons/{code}`, 주문 `/api/orders/{id}`.
- 멱등 기록에는 **경로만**(`/api/orders/42`) 저장하고, 재생할 때 현재 컨텍스트 경로를 기준으로 절대 URI를 다시 만든다(§6.5).

### 1.7 경로 변수
- `{id}`는 `long`이다. 숫자가 아니거나 범위를 넘으면(`/api/orders/abc`) `MethodArgumentTypeMismatchException` → **400**.
- `{code}`(쿠폰)는 아무 문자열이나 받는다. 형식을 검증하지 않으며, 없으면 404 `COUPON_NOT_FOUND`. `[DECISION]` 조회 키 형식 위반을 400으로 만들라는 요구가 없다.

---

## 2. 리소스 표현 (응답 스키마)

### 2.1 ProductResponse (R1.3)
| 필드 | JSON 타입 | null | 설명 |
|---|---|---|---|
| `id` | integer(int64) | N | |
| `name` | string | N | |
| `price` | integer(int64) | N | |
| `stock` | integer | N | 판매되지 않은 보유 수량 |
| `reserved` | integer | N | 결제 대기 주문이 잡아 둔 수량 |
| `available` | integer | N | `stock − reserved` (계산값, 저장하지 않음) |

### 2.2 CouponResponse (R2.3)
| 필드 | JSON 타입 | null | 설명 |
|---|---|---|---|
| `code` | string | N | |
| `type` | string | N | `FIXED` \| `RATE` |
| `value` | integer(int64) | N | FIXED=원, RATE=% |
| `minOrderAmount` | integer(int64) | N | 생략 시 0 |
| `maxDiscountAmount` | integer(int64) | **Y** | null = 제한 없음 |
| `totalQuantity` | integer(int64) | N | |
| `usedCount` | integer(int64) | N | 사용 중인 주문 수 |
| `validFrom` | string(date-time, UTC `Z`) | N | |
| `validUntil` | string(date-time, UTC `Z`) | N | |

### 2.3 OrderResponse (R3.5) — 생성·조회·결제·취소·배송·목록 원소가 모두 이 형태
| 필드 | JSON 타입 | null | 설명 |
|---|---|---|---|
| `id` | integer(int64) | N | |
| `userId` | string | N | 주문 생성 시 `X-User-Id` |
| `status` | string | N | `PENDING_PAYMENT`·`PAID`·`PAYMENT_FAILED`·`EXPIRED`·`CANCELLED`·`SHIPPED`·`DELIVERED`·`REFUNDED` |
| `items` | array | N | 요청 순서 유지(`line_no` 오름차순) |
| `items[].productId` | integer(int64) | N | |
| `items[].quantity` | integer | N | |
| `items[].unitPrice` | integer(int64) | N | 주문 시점 상품 가격 |
| `couponCode` | string | **Y** | 쿠폰을 쓰지 않았으면 null |
| `subtotal` | integer(int64) | N | Σ(unitPrice×quantity) |
| `discount` | integer(int64) | N | 쿠폰이 없으면 0 |
| `totalPrice` | integer(int64) | N | subtotal − discount |
| `createdAt` | string(date-time) | N | |
| `expiresAt` | string(date-time) | N | createdAt + ORDER_PAYMENT_TTL (생성 시 고정) |
| `paidAt` | string(date-time) | **Y** | 결제 승인 시각. PAID·SHIPPED·DELIVERED·REFUNDED에서만 값이 있음 |

### 2.4 OrderPageResponse (R9.1)
| 필드 | JSON 타입 | null | 설명 |
|---|---|---|---|
| `content` | array<OrderResponse> | N | 결과가 없으면 `[]` |
| `nextCursor` | string | **Y** | 다음 페이지가 없으면 null |

---

## 3. 엔드포인트 일람

| # | 메서드·경로 | 요구 | 성공 | 가능한 오류 |
|---|---|---|---|---|
| E1 | `POST /api/products` | R1.1·R1.2 | 201 + Location, ProductResponse | 400 |
| E2 | `GET /api/products/{id}` | R1.3 | 200 ProductResponse | 400, 404 PRODUCT_NOT_FOUND |
| E3 | `POST /api/coupons` | R2.1·R2.2 | 201 + Location, CouponResponse | 400, 409 DUPLICATE_COUPON_CODE |
| E4 | `GET /api/coupons/{code}` | R2.3 | 200 CouponResponse | 404 COUPON_NOT_FOUND |
| E5 | `POST /api/orders` | R3·R4 | 201 + Location, OrderResponse | 400, 422, 409 IDEMPOTENCY_IN_PROGRESS, 404 PRODUCT/COUPON_NOT_FOUND, 409 INSUFFICIENT_STOCK / COUPON_NOT_APPLICABLE / COUPON_EXHAUSTED |
| E6 | `GET /api/orders/{id}` | R3.5 | 200 OrderResponse | 400, 404 ORDER_NOT_FOUND |
| E7 | `GET /api/orders` | R9 | 200 OrderPageResponse | 400 |
| E8 | `POST /api/orders/{id}/pay` | R4·R5 | 200 OrderResponse | 400, 422, 409 IDEMPOTENCY_IN_PROGRESS, 404, 409 INVALID_STATE, 402, 503 |
| E9 | `POST /api/orders/{id}/cancel` | R7 | 200 OrderResponse | 400, 404, 409 INVALID_STATE, 503 |
| E10 | `POST /api/orders/{id}/ship` | R8 | 200 OrderResponse | 400, 404, 409 INVALID_STATE |
| E11 | `POST /api/orders/{id}/deliver` | R8 | 200 OrderResponse | 400, 404, 409 INVALID_STATE |

E9~E11은 요청 본문이 없다(보내도 무시). `X-User-Id`·`Idempotency-Key`도 요구하지 않는다(보내도 무시). `[DECISION]` 소유권 검사는 하지 않는다 — 인증·인가는 범위 밖이다.

---

## 4. 엔드포인트 상세

### E1. `POST /api/products` (R1.1, R1.2)
요청:
```json
{ "name": "키보드", "price": 45000, "stock": 10 }
```
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `name` | string | Y | `@NotBlank`, `@Size(max=100)`. 그대로 저장(trim 안 함) |
| `price` | integer | Y | 1 ≤ price ≤ 10,000,000 |
| `stock` | integer | Y | 0 ≤ stock ≤ 1,000,000 |

처리 순서: ① 400 검증 → ② INSERT(`reserved=0`, `created_at=now`) → 201.
응답: `201 Created`, `Location: …/api/products/{id}`, 본문 ProductResponse(`reserved=0`, `available=stock`).

### E2. `GET /api/products/{id}` (R1.3)
① `{id}` 파싱 실패 → 400 → ② 없으면 404 `PRODUCT_NOT_FOUND` → 200 ProductResponse.

### E3. `POST /api/coupons` (R2.1, R2.2)
요청:
```json
{
  "code": "WELCOME10", "type": "RATE", "value": 10,
  "minOrderAmount": 10000, "maxDiscountAmount": 5000, "totalQuantity": 100,
  "validFrom": "2026-01-01T00:00:00+09:00", "validUntil": "2026-12-31T23:59:59+09:00"
}
```
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `code` | string | Y | `^[A-Z0-9]{4,20}$` |
| `type` | string | Y | `FIXED` \| `RATE` (대소문자 정확히 일치. DTO는 String + `@Pattern`) |
| `value` | integer | Y | FIXED: ≥ 1 / RATE: 1~100 (교차 검증) |
| `minOrderAmount` | integer | N | 생략하거나 null이면 0. 값이 있으면 ≥ 0 |
| `maxDiscountAmount` | integer | N | 생략하거나 null이면 제한 없음(null 저장). 값이 있으면 ≥ 1 |
| `totalQuantity` | integer | Y | ≥ 1 |
| `validFrom` | string | Y | 오프셋 포함 ISO-8601 (§1.2) |
| `validUntil` | string | Y | 오프셋 포함 ISO-8601, `validFrom < validUntil`(엄격) |

- `[DECISION]` `validFrom`/`validUntil`은 필수다(`validFrom < validUntil` 규칙은 두 값이 다 있어야 성립한다). 과거의 `validUntil`도 허용한다(금지 규정 없음).
- 교차 검증(`value` vs `type`, `validFrom < validUntil`)은 단일 필드 검증이 모두 통과한 뒤 수행한다. 결과는 어차피 모두 400이다.

처리 순서: ① 400 → ② INSERT. `uk_coupons_code` 위반이면 409 `DUPLICATE_COUPON_CODE` → 201.
- 중복 판정은 **DB 유니크 제약이 최종 판정**이다(동시 등록 경합 대비). 서비스는 `existsByCode`로 먼저 확인해 409를 던지고, `saveAndFlush`에서 `DataIntegrityViolationException`(제약명 `uk_coupons_code`)이 나도 409로 매핑한다.

응답: `201`, `Location: …/api/coupons/{code}`, CouponResponse(`usedCount=0`).

### E4. `GET /api/coupons/{code}` (R2.3)
없으면 404 `COUPON_NOT_FOUND` → 200 CouponResponse.

### E5. `POST /api/orders` (R3, R4)
헤더: `X-User-Id`(필수), `Idempotency-Key`(필수).
요청:
```json
{ "items": [ { "productId": 1, "quantity": 2 }, { "productId": 3, "quantity": 1 } ], "couponCode": "WELCOME10" }
```
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `items` | array | Y | 원소 1~20개, 원소 null 금지(`List<@NotNull @Valid Item>`) |
| `items[].productId` | integer | Y | null 금지. 존재 여부는 404 단계에서 판정 |
| `items[].quantity` | integer | Y | 1~1,000 |
| (교차) | | | 같은 `productId` 중복 금지 → 400 (`errors[].field = "items"`) |
| `couponCode` | string | N | 생략하거나 null이면 쿠폰 없음. 값이 있으면 공백만으로 된 문자열 금지(400). 존재 여부는 404 단계에서 판정 |

- `[DECISION]` `productId`의 양수 여부는 검사하지 않는다(0·음수는 "없는 상품" → 404). R3.2가 나열한 400 조건에 없기 때문이다.
- `[DECISION]` `couponCode`가 형식(`^[A-Z0-9]{4,20}$`)에 맞지 않아도 400이 아니라 조회 → 404 `COUPON_NOT_FOUND`다. R3.2가 이 필드를 "생략 가능"으로만 규정하기 때문이다.

**처리 순서 (C3 구체화)**
1. **400** — 헤더(`X-User-Id`, `Idempotency-Key`), JSON 파싱, 필드 검증, `productId` 중복. 여기서 실패하면 멱등 기록을 만들지 않는다.
2. **멱등 키(422·409)** — §6 절차, scope `ORDER_CREATE`. 불일치 → 422 `IDEMPOTENCY_KEY_MISMATCH`. 지문이 같고 COMPLETED → **재생**(처리 없이 최초 201 + Location + 본문 그대로). 지문이 같고 IN_PROGRESS → 409 `IDEMPOTENCY_IN_PROGRESS`. 새 키면 IN_PROGRESS 기록을 커밋하고 진행한다.
3. 비즈니스 트랜잭션 시작. `now = clock.instant()` (쿠폰 유효기간 판정 시각이자 `createdAt`, µs로 자름).
4. **404** — (a) 상품: `productId` 집합을 **id 오름차순으로 `SELECT … FOR UPDATE`** 한다(§10). 하나라도 없으면 404 `PRODUCT_NOT_FOUND`(detail에 없는 id 목록). (b) 쿠폰: `couponCode`가 있으면 `SELECT … FROM coupons WHERE code=? FOR UPDATE`. 없으면 404 `COUPON_NOT_FOUND`. 상품 404가 쿠폰 404보다 먼저다. `[DECISION]`
5. **409 재고** — 항목마다 `available = stock − reserved ≥ quantity`. 하나라도 부족하면 409 `INSUFFICIENT_STOCK`.
6. **409 쿠폰** (쿠폰이 있을 때) — `subtotal` 계산(§5) 후:
   - (a) `validFrom ≤ now < validUntil` 위반 → 409 `COUPON_NOT_APPLICABLE`
   - (b) `subtotal < minOrderAmount` → 409 `COUPON_NOT_APPLICABLE`
   - (c) 같은 `userId`가 이 쿠폰을 **활성 상태 주문**(`PENDING_PAYMENT`·`PAID`·`SHIPPED`·`DELIVERED`)에서 쓰고 있음 → 409 `COUPON_NOT_APPLICABLE`
   - (d) `usedCount == totalQuantity` → 409 `COUPON_EXHAUSTED`
   - `[DECISION]` R2.5의 서술 순서대로 NOT_APPLICABLE(a→b→c)을 EXHAUSTED(d)보다 먼저 본다.
7. 반영 — 각 상품 `reserved += quantity`, 쿠폰 `used_count += 1`, `orders` INSERT(`status=PENDING_PAYMENT`, `created_at=now`, `expires_at=now+TTL`, `paid_at=null`), `order_items` INSERT(`unit_price = product.price`, `line_no` = 요청 순서 0..n-1), 멱등 기록을 COMPLETED로 갱신(201, 본문, location 경로). **모두 한 트랜잭션에서 커밋한다**(R3.4 원자성).
8. 4~6 중 어느 단계에서 실패하든 트랜잭션을 롤백한다(부분 반영 없음). 이어서 멱등 IN_PROGRESS 기록을 별도 트랜잭션으로 삭제하고(§6.4) 오류를 응답한다.

응답: `201`, `Location: …/api/orders/{id}`, OrderResponse(`status=PENDING_PAYMENT`, `paidAt=null`).

### E6. `GET /api/orders/{id}` (R3.5)
① `{id}` 파싱 → 400 → ② 없으면 404 `ORDER_NOT_FOUND` → 200. DB에 저장된 상태를 그대로 반환한다(만료 반영은 §8 스케줄러가 2초 안에 보장).

### E7. `GET /api/orders` (R9) — §9 상세
쿼리 파라미터:
| 이름 | 타입 | 기본 | 규칙 | 위반 |
|---|---|---|---|---|
| `userId` | string | 없음 | 있으면 공백만으로 된 문자열 불가, 1~50자 | 400 |
| `status` | string | 없음 | 있으면 8개 상태 이름 중 하나(대소문자 정확히 일치) | 400 |
| `size` | integer | 20 | 1~100. 정수가 아니면 400 | 400 |
| `cursor` | string | 없음 | §9.2 형식 | 400 |

- `[DECISION]` 파라미터가 있지만 빈 문자열(`?status=`, `?cursor=`, `?size=`)이면 "정의되지 않은 값/해석 불가"로 보고 400이다.
- `userId`와 `status`를 둘 다 주면 AND로 결합한다.

### E8. `POST /api/orders/{id}/pay` (R4, R5)
헤더: `Idempotency-Key`(필수), `X-User-Id`(선택, 지문에만 쓰임).
요청: `{ "cardToken": "tok_visa_4242" }` — `cardToken`은 필수이고 공백만으로 된 문자열 불가(`@NotBlank`). 길이 제한 없음.

**처리 순서 (C3 구체화)**
1. **400** — `{id}` 파싱, `Idempotency-Key`, JSON, `cardToken`.
2. **멱등 키(422·409)** — scope `ORDER_PAY`. E5와 같은 규칙. 재생 시 최초 200 + 본문.
3. 비즈니스 트랜잭션(`TransactionTemplate`, §10.3) 시작 — `SELECT … FROM orders WHERE id=? FOR UPDATE`가 **이 트랜잭션에서 그 주문에 대한 첫 접근**이어야 한다.
4. **404** — 주문이 없으면 404 `ORDER_NOT_FOUND`.
5. **409** — `status ≠ PENDING_PAYMENT` 또는 `now ≥ expiresAt` → 409 `INVALID_STATE`. (`now`는 락을 얻은 직후에 읽는다.)
6. `totalPrice == 0` → PG를 호출하지 않고 7-승인으로 간다(R5.7, `payment_id = null`).
7. PG 호출(§7) — **주문 행 락을 쥔 채로** 호출한다(R10.5).
   - **승인(APPROVED)** → 상품을 id 오름차순으로 `FOR UPDATE` → 각 상품 `stock -= q`, `reserved -= q`. 주문은 `status=PAID`, `paid_at=now'`(PG 응답 직후 시각, µs), `payment_id` 저장. 멱등 기록 COMPLETED(200, 본문). 커밋 → **200** OrderResponse.
   - **거절(DECLINED)** → 상품 `FOR UPDATE`(오름차순) → `reserved -= q`. 쿠폰이 있으면 쿠폰 `FOR UPDATE` → `used_count -= 1`. 주문은 `status=PAYMENT_FAILED`, `payment_id` 저장. **커밋한 다음** 멱등 기록을 삭제하고(비2xx는 저장하지 않음 — R4.4) **402** `PAYMENT_DECLINED`.
   - **장애**(5xx / 연결 실패 / 2초 초과 / 해석 불가 응답) → **롤백**(주문·재고·쿠폰 불변 — R5.6) → 멱등 기록 삭제 → **503** `PAYMENT_GATEWAY_UNAVAILABLE`.

- 구현 주의: 402는 "상태 변경을 커밋하고도 오류를 응답"하는 경로다. `@Transactional` 메서드 안에서 예외를 던지면 롤백되므로, 트랜잭션 콜백은 결과(`PayOutcome.APPROVED/DECLINED`)를 **반환**하고, 커밋이 끝난 뒤 바깥(파사드)에서 `PaymentDeclinedException`을 던진다.
- `[DECISION]` 만료 판정은 락을 얻은 시점에 한다. 그 뒤 PG 호출 중에 `expiresAt`이 지나도 PG 결과(승인)를 존중해 PAID로 확정한다. 이 동안 스케줄러는 `SKIP LOCKED`로 그 주문을 건너뛰므로 충돌하지 않는다(§8.3).

### E9. `POST /api/orders/{id}/cancel` (R7)
1. 400(`{id}`) → 2. 트랜잭션, 주문 `FOR UPDATE` → 없으면 404 `ORDER_NOT_FOUND`.
3. 상태별 처리:
   - `PENDING_PAYMENT` 이고 `now < expiresAt` → 상품 `FOR UPDATE`(오름차순) `reserved -= q`, 쿠폰 `used_count -= 1`, `status=CANCELLED` → 200. (R7.2)
   - `PENDING_PAYMENT` 이고 `now ≥ expiresAt` → 409 `INVALID_STATE`. 상태는 바꾸지 않고, EXPIRED 전이는 스케줄러가 0.5초 안에 처리한다. `[DECISION]` 만료 시각이 지난 주문은 사실상 EXPIRED이므로 취소를 허용하지 않는다. 결제(R5.2)와 일관된다.
   - `PAID` → `payment_id`가 있으면 PG 환불(§7.3)을 **주문 락을 쥔 채로** 호출한다.
     - 성공 → 상품 `FOR UPDATE`(오름차순) `stock += q`, 쿠폰 `used_count -= 1`, `status=REFUNDED`(`paid_at`은 유지) → 200. (R7.3)
     - 장애 → 롤백 → 503 `PAYMENT_GATEWAY_UNAVAILABLE`(주문·재고·쿠폰 불변).
     - `[DECISION]` `payment_id`가 null이면(=`totalPrice` 0으로 PG 없이 결제된 주문) PG를 호출하지 않고 바로 환불 성공으로 처리한다.
   - 그 밖(`PAYMENT_FAILED`·`EXPIRED`·`CANCELLED`·`SHIPPED`·`DELIVERED`·`REFUNDED`) → 409 `INVALID_STATE`. (R7.4)

### E10. `POST /api/orders/{id}/ship` / E11. `POST /api/orders/{id}/deliver` (R8)
1. 400 → 2. 트랜잭션, 주문 `FOR UPDATE` → 404 → 3. `ship`: `PAID`이면 `SHIPPED`, 아니면 409 `INVALID_STATE`. `deliver`: `SHIPPED`이면 `DELIVERED`, 아니면 409 → 200 OrderResponse. 재고·쿠폰 변화는 없다.

---

## 5. 금액 계산 (R2.4)

```
subtotal = Σ (unitPrice_i × quantity_i)               // unitPrice = 주문 시점 products.price
if coupon == null: discount = 0
else:
  d = (type == FIXED) ? value : floor(subtotal × value / 100)   // 음이 아닌 long의 정수 나눗셈 = floor
  if maxDiscountAmount != null: d = min(d, maxDiscountAmount)
  d = min(d, subtotal)
  discount = d
totalPrice = subtotal − discount                     // ≥ 0. 0이면 R5.7 경로
```
쿠폰 적용 가능 여부(§E5 6단계)의 `minOrderAmount` 비교 기준은 **할인 전 subtotal**이다.

---

## 6. 멱등성 (R4)

### 6.1 키 공간
- `(scope, idempotency_key)`가 유일하다. scope는 `ORDER_CREATE`(E5)와 `ORDER_PAY`(E8)이고 서로 독립이다(R4.1). 같은 키 문자열을 두 엔드포인트에서 각각 써도 충돌하지 않는다.
- 키는 사용자별이 아니라 scope 안에서 **전역**이다. 다른 사용자가 같은 키를 쓰면 지문의 `X-User-Id`가 달라 422가 된다.

### 6.2 저장 대상
- **2xx 응답만** COMPLETED로 저장한다: `response_status`, `response_body`(직렬화된 JSON 문자열 원문), `response_location`(경로, E5만).
- 오류로 끝나면(400 제외 — 400은 기록 생성 전에 끝난다) IN_PROGRESS 기록을 **삭제**한다. 같은 요청으로 다시 시도할 수 있다(R4.4).

### 6.3 요청 지문 (R4.2 "같은 X-User-Id·경로·본문")
```
fingerprint = lowercase_hex( SHA-256( UTF-8(
    scope + "\n" +
    "POST" + "\n" +
    canonicalPath + "\n" +
    (xUserId == null ? "N" : "U:" + xUserId) + "\n" +
    canonicalBody ) ) )                      // 64자
```
- `canonicalPath`: E5 → `/api/orders`. E8 → `/api/orders/{id}`에 **파싱된 long id**를 넣은 `/api/orders/42/pay`(`042` 같은 표기 차이를 흡수).
- `canonicalBody`: **검증을 통과한 요청 DTO**를 고정 필드 순서로 직렬화한 압축 JSON이다(공백 없음, null 포함, 배열 순서 유지).
  - E5: `{"items":[{"productId":1,"quantity":2},…],"couponCode":null}` — 항목 순서가 다르면 다른 요청이다(응답 `items` 순서가 달라지므로).
  - E8: `{"cardToken":"tok_…"}`
- `[DECISION]` 원문 바이트가 아니라 **의미 기준**으로 비교한다. 공백·키 순서·알 수 없는 필드·`"couponCode":null` 대 생략 같은 차이는 같은 요청으로 본다.
- 비교는 저장된 `request_fingerprint`와 문자열이 같은지로 한다.

### 6.4 절차 (컨트롤러 → 파사드, 파사드는 트랜잭션 없음)
```
① [Tx-A, 짧게, 즉시 커밋]
   INSERT INTO idempotency_records(scope, idempotency_key, request_fingerprint, status, created_at)
   VALUES (?, ?, ?, 'IN_PROGRESS', now) ON CONFLICT (scope, idempotency_key) DO NOTHING RETURNING id
   ├─ 삽입됨 → 소유권 획득, ②로
   └─ 충돌 → SELECT 기존 행
        ├─ 행 없음(그 사이 삭제됨) → ①을 다시 시도(최대 3회, 그래도 실패하면 409 IDEMPOTENCY_IN_PROGRESS)
        ├─ fingerprint 다름          → 422 IDEMPOTENCY_KEY_MISMATCH   (상태와 무관하게 먼저 판정)
        ├─ COMPLETED                 → 재생(§6.5)
        └─ IN_PROGRESS               → 409 IDEMPOTENCY_IN_PROGRESS
② [Tx-B, 비즈니스] 처리. 2xx면 같은 Tx-B 안에서
   UPDATE idempotency_records SET status='COMPLETED', response_status=?, response_body=?, response_location=?, completed_at=?
   WHERE id=? AND status='IN_PROGRESS'      → 비즈니스 반영과 응답 저장이 원자적
③ 비2xx(예외든 402처럼 커밋 후 오류든)면 Tx-B가 끝난 뒤 [Tx-C]
   DELETE FROM idempotency_records WHERE id=? AND status='IN_PROGRESS'
```
- Tx-A·Tx-B·Tx-C는 **중첩하지 않고 순차 실행한다**(각자 커넥션을 빌렸다가 반납). 중첩(REQUIRES_NEW를 바깥 트랜잭션 안에서)하면 동시성 테스트에서 커넥션 풀이 고갈되어 교착할 수 있다.
- 동시성(R4.5): 같은 키로 동시에 온 요청 중 ①의 INSERT에서 이긴 하나만 처리한다. PostgreSQL은 커밋되지 않은 경쟁 INSERT가 끝날 때까지 `ON CONFLICT`를 대기시키는데, Tx-A는 즉시 커밋하므로 대기는 아주 짧다. 나머지는 처리 중이면 409 `IDEMPOTENCY_IN_PROGRESS`, 완료 후에 왔으면 재생 응답을 받는다.
- C3 단계: ①의 422/409 판정은 404·409(비즈니스)보다 먼저다. 400은 ① 이전에 끝난다.

### 6.5 재생 응답
- 상태코드 = `response_status`(E5: 201, E8: 200), 본문 = `response_body` 원문 그대로, `Content-Type: application/json`.
- E5는 `Location` = 현재 컨텍스트 경로 + `response_location`(예: `http://localhost:8080` + `/api/orders/42`).
- 재생은 **처리하지 않는다**. 그 사이 주문 상태가 바뀌었더라도 최초 응답의 스냅샷을 돌려준다(R4.2 "최초 응답과 같은").
- 원본 응답과 재생 응답의 본문이 바이트 단위로 같도록, 원본 응답도 저장한 것과 같은 `ObjectMapper`로 직렬화한다.

### 6.6 한계
- `[LIMIT]` Tx-A 커밋과 Tx-C 사이에 프로세스가 죽으면 IN_PROGRESS가 남아, 같은 키에 영구히 409를 준다. 멱등 키 만료는 범위 밖이므로 처리하지 않는다.
- `[LIMIT]` 오류로 끝난 결제 키를 클라이언트가 **다른 주문**에 재사용하면, PG가 같은 `Idempotency-Key`에 최초 결과를 돌려주므로 잘못 연결될 수 있다. R5.3이 키를 "그대로" 쓰라고 하므로 키에 손대지 않는다. 키 재사용 금지는 클라이언트 책임이다.

---

## 7. 외부 PG 클라이언트 계약

### 7.1 설정·전송
- 기본 URL: `payment.gateway.url` = `${PAYMENT_GATEWAY_URL:http://localhost:9090}`.
- 타임아웃: 연결 `payment.gateway.connect-timeout`(기본 `PT2S`), 응답 `payment.gateway.read-timeout`(기본 `PT2S`).
- 구현: Spring `RestClient` + `JdkClientHttpRequestFactory`. `HttpClient`는 `version(HTTP_1_1)`, `connectTimeout(2s)`, 팩토리는 `setReadTimeout(2s)`. HTTP/1.1을 고정하는 이유는 테스트의 JDK `HttpServer`와 h2c 업그레이드 문제를 피하기 위해서다.
- `PaymentGatewayClient`는 HTTP를 모르는 결과 타입을 반환한다: `PaymentResult.approved(paymentId)` / `PaymentResult.declined(paymentId)`. 장애는 `PaymentGatewayUnavailableException`.

### 7.2 결제 `POST {url}/v1/payments`
- 헤더: `Content-Type: application/json`, `Idempotency-Key: <클라이언트가 E8에 보낸 키 그대로>`(R5.3)
- 본문: `{"orderId": <long>, "amount": <long totalPrice>, "cardToken": "<그대로>"}`
- 해석:
| PG 응답 | 결과 |
|---|---|
| 200 `{paymentId, status:"APPROVED"}` | 승인 → E8 200 |
| 200 `{paymentId, status:"DECLINED"}` | 거절 → E8 402 `PAYMENT_DECLINED` |
| 5xx | 장애 → 503 |
| 연결 실패(거부·DNS·리셋 등 `ResourceAccessException`) | 장애 → 503 |
| 2초 안에 응답 없음 | 장애 → 503 |
| 4xx, 200인데 본문 해석 불가·`status`가 그 밖의 값·`paymentId` 누락 | `[DECISION]` 장애로 취급 → 503(상태 불변). 계약 밖 응답을 "결제됨/거절됨"으로 단정하지 않는다 |
- `totalPrice == 0`이면 호출하지 않는다(R5.7).

### 7.3 환불 `POST {url}/v1/payments/{paymentId}/refund`
- 본문 없음. 헤더에 `Idempotency-Key`를 넣지 않는다(계약에 없음).
- 200 `{paymentId, status:"REFUNDED"}` → 성공. 그 밖의 모든 경우(5xx·연결 실패·2초 초과·4xx·해석 불가) → 503, 상태 불변(R7.3).

### 7.4 트랜잭션과의 관계
- PG 호출은 **주문 행 락(`FOR UPDATE`)을 쥔 비즈니스 트랜잭션 안에서** 한다(최대 2초). 이 동안 상품·쿠폰 락은 쥐지 않는다(주문 생성이 막히지 않게). 커넥션 점유 시간이 길어지므로 Hikari `maximum-pool-size`를 20으로 올린다(§12). `[DECISION]`
- PG가 승인했는데 그 뒤 DB 커밋이 실패하면 클라이언트는 503/500을 받는다. 같은 키로 다시 시도하면 PG가 같은 결과(승인)를 돌려주므로 결국 맞춰진다.

---

## 8. 결제 만료 (R6)

### 8.1 방식 — 스케줄러 단독 `[DECISION]`
- `OrderExpirationScheduler`: `@Scheduled(fixedDelayString = "${order.expiration.scan-interval-ms:500}")`. 500ms 간격이면 `expiresAt` 이후 반영까지 최악의 경우 ≈ 0.5초 + 처리 시간으로, R6.2의 2초 안에 든다.
- `@ConditionalOnProperty(name = "order.expiration.enabled", havingValue = "true", matchIfMissing = true)` — 특정 테스트에서 끌 수 있다.
- 조회 시 지연 만료(읽기 경로에서 상태 변경)는 하지 않는다. 주문 조회만 EXPIRED로 바뀌고 상품 `reserved`·쿠폰 `usedCount`가 아직 복원되지 않은 불일치 시점이 생기기 때문이다. 대신 **쓰기 경로**(결제·취소)는 `now ≥ expiresAt`인 PENDING_PAYMENT를 사실상 EXPIRED로 보고 409를 준다(§E8, §E9).

### 8.2 한 회차의 동작
```
loop:
  ids = SELECT id FROM orders
        WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now      -- :now = clock.instant()
        ORDER BY expires_at, id LIMIT :batch                           -- batch = order.expiration.batch-size (기본 100)
  for id in ids:                       -- 주문마다 별도 트랜잭션(TransactionTemplate)
    SELECT * FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED
      → 행이 안 나오면 skip (결제/취소가 처리 중 → 다음 회차에 다시 봄)
    다시 확인: status = PENDING_PAYMENT AND expires_at <= :now, 아니면 skip
    상품 FOR UPDATE (id 오름차순) → reserved -= q
    쿠폰 있으면 FOR UPDATE → used_count -= 1
    status = 'EXPIRED', updated_at = now
  if ids.size < batch: break
```
- 주문 하나에 트랜잭션 하나를 쓰는 이유: 여러 주문을 한 트랜잭션에서 처리하면 주문 A의 상품 5, 주문 B의 상품 3 순으로 잠기게 되어 전역 락 순서(§10.1)가 깨지고 교착이 생길 수 있다.
- 한 주문의 처리가 실패해도 로그만 남기고 다음 주문으로 넘어간다.

### 8.3 경합
- 결제(E8)가 PG를 기다리며 주문 락을 쥐고 있으면 스케줄러는 `SKIP LOCKED`로 건너뛴다. 결제가 승인되면 PAID이므로 이후 대상이 아니고, 503으로 롤백되면 다음 회차에 EXPIRED로 바뀐다.
- 스케줄러가 먼저 락을 잡으면 결제는 락을 기다렸다가 EXPIRED를 보고 409 `INVALID_STATE`를 준다.
- 테스트에서 여러 Spring 컨텍스트가 캐시되어 같은 DB에 스케줄러 여러 개가 돌아도 `SKIP LOCKED` + 재확인 덕분에 안전하다.

### 8.4 TTL
- `order.payment-ttl` = `${ORDER_PAYMENT_TTL:PT15M}` → `OrderProperties.paymentTtl`(`java.time.Duration`). ISO-8601(`PT3S`)과 Boot 단축형(`3s`) 모두 바인딩된다.
- 0 이하이면 기동에 실패하게 한다(`@Validated` + 검증). DB CHECK `expires_at > created_at`와 맞추기 위해서다.
- `expiresAt`은 생성 시 계산해 저장하므로, TTL을 바꿔도 기존 주문에는 영향이 없다.

---

## 9. 주문 목록 (R9)

### 9.1 정렬·조회
```
WHERE (userId 필터) AND (status 필터)
  AND ( cursor 없음 OR created_at < :cAt OR (created_at = :cAt AND id < :cId) )
ORDER BY created_at DESC, id DESC
LIMIT :size + 1
```
- JPQL/Criteria로 **동적 생성**한다(필터가 없으면 조건을 빼는 방식). `:param IS NULL OR …` 패턴은 PostgreSQL의 null 파라미터 타입 추론 문제를 일으키므로 쓰지 않는다.
- `size+1`개를 읽어 `size`개보다 많으면 `nextCursor` = **반환한 마지막 원소**의 `(createdAt, id)`를 인코딩한 값이고, 아니면 null이다.
- `items`는 `hibernate.default_batch_fetch_size: 100`으로 한 번에 묶어 로딩한다(N+1 방지). DTO 변환은 트랜잭션 안에서 끝낸다(`open-in-view: false`).

### 9.2 커서 인코딩
- 원문: `"<createdAt epoch µs>:<id>"` (예: `1791602100123456:42`) → **Base64URL, 패딩 없음**.
- 해석: Base64URL 디코딩 실패, UTF-8 아님, 정규식 `^-?\d{1,19}:\d{1,19}$` 불일치, `long` 오버플로, `id < 1` → **400 `VALIDATION_ERROR`** (`errors[].field="cursor"`).
- 커서에는 필터가 들어 있지 않다. 필터를 바꿔 같은 커서를 보내도 그 필터로 "커서보다 뒤"를 돌려줄 뿐이다.

### 9.3 R9.5 보장 논리
- 정렬 키 `(created_at, id)`는 `id`가 유일하므로 **전순서**이고, 두 값 모두 **불변**이다(`created_at`은 `updatable=false`, 갱신 경로 없음).
- 다음 페이지 조건은 "커서보다 엄격히 뒤"이므로, 첫 페이지 시점에 있던 주문은 정렬된 목록 안에서 위치가 바뀌지 않는다. 그래서 어떤 페이지에서 반환된 원소가 다음 페이지에 다시 나오지 않고(중복 없음), 연속 구간을 그대로 이어 가므로 빠지는 것도 없다(누락 없음).
- 새로 생긴 주문은 `created_at ≥ 첫 페이지 조회 시각`이어서 대개 커서보다 앞에 놓이므로 이어지는 페이지에 나타나지 않는다. 생성 시각을 먼저 잡고 늦게 커밋된 주문은 뒤 페이지에 나타날 수 있는데, 이는 "첫 페이지 시점에 있던 주문"이 아니므로 R9.5와 충돌하지 않는다.
- offset 페이지네이션은 새 주문이 앞에 끼면 원소가 밀려 중복이 생기므로 쓰지 않는다.
- (상태 필터를 걸고 페이지를 넘기는 사이 주문 상태가 바뀌면 그 주문은 필터에서 빠질 수 있다. 이는 R9.5가 다루지 않는 경우다.)

---

## 10. 동시성 (R10)

### 10.1 전역 락 순서 (교착 방지 — R10.4)
**`idempotency_records`(Tx-A, 별도 커밋) → `orders`(1행) → `products`(id 오름차순) → `coupons`(1행)**
- 모든 흐름이 이 순서의 부분 수열로만 락을 잡는다:

| 흐름 | 락 순서 |
|---|---|
| 주문 생성 E5 | products(오름차순) → coupon |
| 결제 승인 E8 | order → (PG) → products(오름차순) |
| 결제 거절 E8 | order → (PG) → products(오름차순) → coupon |
| 취소 E9 | order → [PG 환불] → products(오름차순) → coupon |
| 배송 E10·E11 | order |
| 만료 스케줄러 | order(SKIP LOCKED) → products(오름차순) → coupon |

- 상품 락: `@Lock(PESSIMISTIC_WRITE) @Query("select p from Product p where p.id in :ids order by p.id")`. PostgreSQL은 정렬된 순서로 행을 잠근다(LockRows가 Sort 위에 있음). Hibernate 6는 `FOR NO KEY UPDATE`를 생성하는데, 이것끼리는 충돌하고 FK 검사의 `KEY SHARE`와는 충돌하지 않는다.
- **락 조회가 그 엔티티의 첫 로딩이어야 한다.** 같은 트랜잭션에서 미리 로딩해 둔 엔티티는 1차 캐시의 오래된 상태가 그대로 반환된다.
- 격리 수준은 READ COMMITTED(기본)이다. 모든 불변식 검사는 락을 얻은 **뒤에** 하고, READ COMMITTED는 문장마다 새 스냅샷을 쓰므로 직전 락 보유자가 커밋한 값을 본다.

### 10.2 시나리오별 근거
- **R10.1** 같은 상품 행 락으로 20건이 직렬화된다. `available ≥ 1` 검사 → `reserved += 1`. 정확히 10건 성공, 그 뒤는 409 `INSUFFICIENT_STOCK`. DB CHECK `reserved <= stock`이 마지막 안전망이다.
- **R10.2** 쿠폰 행 락으로 직렬화되고 `used_count < total_quantity` 검사 → 5건 성공, 10건 409 `COUPON_EXHAUSTED`. CHECK `used_count <= total_quantity`가 안전망이다.
- **R10.3** 같은 쿠폰 행 락으로 직렬화된다. 두 번째부터는 락을 얻은 뒤 "같은 사용자의 활성 주문 존재" 쿼리가 직전 커밋을 보므로 409 `COUPON_NOT_APPLICABLE`이다. 안전망은 부분 유니크 인덱스 `ux_orders_active_coupon_user (coupon_code, user_id) WHERE status IN 활성` — 위반하면 409 `COUPON_NOT_APPLICABLE`로 매핑한다. 정확히 1건이 201이다.
- **R10.4** §10.1의 오름차순 락으로 `[P,Q]`와 `[Q,P]`가 같은 순서(P→Q)로 잠기므로 교착이 없다. 5xx 없이 처리되고 `reserved`가 정확하다.
- **R10.5** 키가 달라 멱등 단계를 모두 통과하지만, 주문 행 `FOR UPDATE`에서 직렬화된다. 첫 요청이 PG를 호출하고 PAID로 커밋하면 나머지는 락을 얻은 뒤 PAID를 보고 409 `INVALID_STATE`. **PG 호출 1회, 성공 1건**이다. 첫 요청이 503(PG 장애)이면 롤백(R5.6: 주문 불변)되고 다음 요청이 자기 키로 PG를 호출한다. 이전 호출은 장애였으므로 "성공 1건" 불변식은 유지된다. 별도의 "결제 중" 마커 컬럼을 쓰지 않는 이유는, 장애 때 그 마커가 남거나 지워져야 해서 R5.6의 "불변"과 충돌하기 때문이다.
- 결제·스케줄러 경합은 §8.3, 결제·취소 경합은 주문 행 락으로 직렬화된다.

### 10.3 트랜잭션 경계 구현 지침
- 결제·취소·만료는 "커밋 후 오류 응답(402)"과 "PG 호출을 사이에 둔 단계"가 있으므로 `TransactionTemplate`으로 경계를 명시한다. 생성·배송·조회는 `@Transactional`이면 충분하다.
- `@Version`(낙관적 락)은 쓰지 않는다. 비관적 락만으로 직렬화되므로 필요 없고, 쓰면 처리되지 않은 `OptimisticLockException`이 500이 될 위험만 생긴다. `[DECISION]`

---

## 11. 에러 계약 (R11)

### 11.1 형식 — RFC 9457
- `Content-Type: application/problem+json`. 핸들러가 `ResponseEntity<ProblemDetail>`에 `contentType(MediaType.APPLICATION_PROBLEM_JSON)`을 **명시**한다(클라이언트의 `Accept: application/json`과 무관하게 고정).
- 필수 필드:
```json
{
  "type": "https://order-service.example.com/problems/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "상품 1의 주문 가능 수량(3)이 요청 수량(5)보다 적습니다.",
  "code": "INSUFFICIENT_STOCK",
  "instance": "/api/orders"
}
```
- `type` = `https://order-service.example.com/problems/` + code를 소문자 kebab-case로 바꾼 값. `instance`는 요청 경로(선택).
- 400에는 확장 필드 `errors: [{ "field": "items[0].quantity", "message": "1 이상이어야 합니다" }]`를 추가한다. 헤더는 `header:X-User-Id`, 쿼리는 `query:size`, 본문 파싱 실패는 `field: "body"`로 쓴다.
- 500 응답에는 내부 메시지·스택·SQL을 노출하지 않는다.
- 구현: `@RestControllerAdvice`가 `ResponseEntityExceptionHandler`를 상속하고, `handleExceptionInternal`을 오버라이드해 **모든** MVC 표준 예외에 `code`를 붙인다.

### 11.2 code 표 (R11.3) 및 예외 매핑
| 상태 | code | title | 발생 예외(도메인/프레임워크) | 발생 지점 |
|---|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Validation Error | `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `HttpMessageNotReadableException`(JSON 파싱·타입 불일치·빈 본문), `MissingRequestHeaderException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`(경로 `{id}`·`size`), `HttpMediaTypeNotSupportedException`, 도메인 `RequestValidationException`(헤더 규칙·productId 중복·쿠폰 교차 검증·시각 파싱·status/cursor/userId 쿼리) | 모든 엔드포인트 |
| 402 | `PAYMENT_DECLINED` | Payment Declined | `PaymentDeclinedException` | E8 |
| 404 | `PRODUCT_NOT_FOUND` | Product Not Found | `ProductNotFoundException` | E2, E5 |
| 404 | `COUPON_NOT_FOUND` | Coupon Not Found | `CouponNotFoundException` | E4, E5 |
| 404 | `ORDER_NOT_FOUND` | Order Not Found | `OrderNotFoundException` | E6, E8~E11 |
| 409 | `INSUFFICIENT_STOCK` | Insufficient Stock | `InsufficientStockException` | E5 |
| 409 | `COUPON_NOT_APPLICABLE` | Coupon Not Applicable | `CouponNotApplicableException`, `DataIntegrityViolationException`(제약 `ux_orders_active_coupon_user`) | E5 |
| 409 | `COUPON_EXHAUSTED` | Coupon Exhausted | `CouponExhaustedException` | E5 |
| 409 | `DUPLICATE_COUPON_CODE` | Duplicate Coupon Code | `DuplicateCouponCodeException`, `DataIntegrityViolationException`(제약 `uk_coupons_code`) | E3 |
| 409 | `INVALID_STATE` | Invalid Order State | `InvalidOrderStateException` | E8~E11 |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Idempotent Request In Progress | `IdempotencyInProgressException` | E5, E8 |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | Idempotency Key Mismatch | `IdempotencyKeyMismatchException` | E5, E8 |
| 503 | `PAYMENT_GATEWAY_UNAVAILABLE` | Payment Gateway Unavailable | `PaymentGatewayUnavailableException` | E8, E9 |

- `[DECISION]` `HttpMediaTypeNotSupportedException`(415)은 요청 형식 위반으로 보고 **400 `VALIDATION_ERROR`** 로 매핑한다. R11.3 표 밖의 상태를 만들지 않기 위해서다.
- `DataIntegrityViolationException`은 원인 체인의 `org.hibernate.exception.ConstraintViolationException#getConstraintName()`(또는 `PSQLException`의 server error message constraint)으로 제약을 구분한다. 매핑표에 없는 제약은 500이다(버그).
- `[DECISION]` 계약 밖 오류(R1~R10이 정의하지 않은 오류)도 ProblemDetail로 응답한다: 없는 경로 `NoResourceFoundException` → 404 `code=RESOURCE_NOT_FOUND`, 405 → `METHOD_NOT_ALLOWED`, 406 → `NOT_ACCEPTABLE`, 그 밖 → 500 `INTERNAL_ERROR`. R11.3의 "code 집합" 규정은 R1~R10이 정의한 오류에 적용하고, 테스트도 그 범위만 검증한다.
- 도메인 예외는 HTTP를 모른다: 공통 부모 `BusinessException(ErrorCode code, String detail)`를 두고, `ErrorCode → HttpStatus/title` 매핑은 핸들러 한 곳에만 둔다.

### 11.3 엔드포인트별 오류 우선순위 요약 (C3)
| 엔드포인트 | 1. 400 | 2. 멱등(422→409) | 3. 404 | 4. 409 | 5. PG |
|---|---|---|---|---|---|
| E1 | 본문 | — | — | — | — |
| E2 | `{id}` | — | 상품 | — | — |
| E3 | 본문(단일 필드 → 교차) | — | — | 코드 중복 | — |
| E4 | — | — | 쿠폰 | — | — |
| E5 | 헤더·본문·중복 | 불일치 422 → 진행 중 409 | 상품 → 쿠폰 | 재고 → 쿠폰(적용불가 → 소진) | — |
| E6 | `{id}` | — | 주문 | — | — |
| E7 | 쿼리 | — | — | — | — |
| E8 | `{id}`·헤더·본문 | 불일치 422 → 진행 중 409 | 주문 | INVALID_STATE | 402 / 503 |
| E9 | `{id}` | — | 주문 | INVALID_STATE | 503(환불) |
| E10·E11 | `{id}` | — | 주문 | INVALID_STATE | — |

---

## 12. 설정 (C4) — `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: order-service
  datasource:
    # SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD 환경 변수는 Boot relaxed binding으로 자동 우선 적용됨.
    url: jdbc:postgresql://localhost:5432/orders     # 로컬 기본값(임의)
    username: order                                   # 로컬 기본값(임의)
    # password: 기본값 없음 — SPRING_DATASOURCE_PASSWORD로만 주입(비밀값 하드코딩 금지)
    hikari:
      maximum-pool-size: 20
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc.time_zone: UTC
        default_batch_fetch_size: 100
  flyway:
    enabled: true            # classpath:db/migration
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      accept-float-as-int: false
      fail-on-trailing-tokens: true
      fail-on-null-for-primitives: true
    mapper:
      allow-coercion-of-scalars: false

server:
  port: ${SERVER_PORT:8080}

order:
  payment-ttl: ${ORDER_PAYMENT_TTL:PT15M}   # relaxed binding으로는 order.payment.ttl이 되므로 명시 placeholder 필수
  expiration:
    enabled: true
    scan-interval-ms: 500
    batch-size: 100

payment:
  gateway:
    url: ${PAYMENT_GATEWAY_URL:http://localhost:9090}
    connect-timeout: PT2S
    read-timeout: PT2S
```
- 바인딩: `OrderProperties(@ConfigurationProperties("order"))` — `Duration paymentTtl`, `Expiration expiration(boolean enabled, long scanIntervalMs, int batchSize)`. `PaymentGatewayProperties(@ConfigurationProperties("payment.gateway"))` — `URI url`, `Duration connectTimeout`, `Duration readTimeout`.
- 테스트에서 덮어쓰기: `@DynamicPropertySource`로 `payment.gateway.url`(가짜 PG 포트)과 `order.payment-ttl`(예: `PT2S`)을 등록한다. 이 값이 `application.yml`·환경 변수보다 우선한다. DB는 `@ServiceConnection`(Testcontainers)이 덮어쓴다.

---

## 13. 상태 전이와 부수 효과 (구현·검증 공통 기준)

| 전이 | 트리거 | products | coupons(`coupon_code`가 있을 때) | orders |
|---|---|---|---|---|
| (생성) → PENDING_PAYMENT | E5 | `reserved += q` | `used_count += 1` | created_at, expires_at |
| PENDING_PAYMENT → PAID | E8 승인 / totalPrice=0 | `stock -= q`, `reserved -= q` | — | paid_at, payment_id(PG 경유 시) |
| PENDING_PAYMENT → PAYMENT_FAILED | E8 거절 | `reserved -= q` | `used_count -= 1` | payment_id |
| PENDING_PAYMENT → EXPIRED | 스케줄러 | `reserved -= q` | `used_count -= 1` | — |
| PENDING_PAYMENT → CANCELLED | E9 | `reserved -= q` | `used_count -= 1` | — |
| PAID → REFUNDED | E9 + PG 환불 성공 | `stock += q` | `used_count -= 1` | paid_at 유지 |
| PAID → SHIPPED | E10 | — | — | — |
| SHIPPED → DELIVERED | E11 | — | — | — |
| 그 밖 | — | 409 `INVALID_STATE` | | |

---

## 14. 테스트 훅 · 의존성 제안 (Phase 2용)

- **DB**: `PostgreSQLContainer<>("postgres:16-alpine")` + `@ServiceConnection`. 컨테이너를 static 싱글톤으로 공유해 컨텍스트 캐시와 함께 재사용한다. Flyway가 V1을 적용하고 `ddl-auto: validate`가 매핑을 검증한다.
- **가짜 PG**: JDK 내장 `com.sun.net.httpserver.HttpServer`(추가 의존성 없음)를 `127.0.0.1:0`에 띄운다. 테스트가 바꿀 수 있는 모드: `APPROVE` / `DECLINE` / `HTTP_500` / `DELAY(ms)`(예: 3000 → 타임아웃) / `REFUND_OK` / `REFUND_500`. 기록 항목: 호출 수, 각 요청의 `Idempotency-Key` 헤더, 본문 `{orderId, amount, cardToken}`. 같은 `Idempotency-Key`에는 최초 결과를 돌려주도록 흉내 낸다.
- **연결 실패(R5.6/R7.3)**: `ServerSocket(0)`으로 포트를 얻고 즉시 닫은 뒤 그 포트를 `payment.gateway.url`로 쓰는 별도 테스트 컨텍스트를 둔다(또는 가짜 PG를 `stop(0)`).
- **만료(R6)**: `order.payment-ttl=PT2S`인 별도 컨텍스트에서 생성 → `expiresAt + 2s` 시점에 주문·상품·쿠폰 조회로 검증한다(스케줄러 주기 500ms).
- **HTTP 클라이언트**: `TestRestTemplate`(`@SpringBootTest(webEnvironment = RANDOM_PORT)`) — 실제 서블릿·Jackson·ProblemDetail 경로를 탄다. 동시성 테스트는 `ExecutorService` + `CountDownLatch`.
- **의존성**: 필수 추가 없음(Testcontainers·starter-test가 이미 있음). 선택: `testImplementation 'org.awaitility:awaitility'`(Boot BOM이 버전 관리) — R6 대기 검증에 쓴다.

---

## 15. 결정·한계 목록 (요약)
- `[DECISION]` 패키지 `com.example.order` + 기능별 하위 패키지(`ordering`) — §0
- `[DECISION]` 응답 시각은 UTC `Z`, µs 정밀도. 입력 오프셋은 보존하지 않는다 — §1.2
- `[DECISION]` X-User-Id "공백 아닌" = 공백만으로 된 값 금지 — §1.5
- `[DECISION]` pay의 X-User-Id는 선택이며 지문에만 쓰인다. 소유권 검사 없음 — §1.5, §3
- `[DECISION]` productId 양수 검사 없음(404), couponCode 형식 검사 없음(404) — §E5
- `[DECISION]` 쿠폰 409는 NOT_APPLICABLE → EXHAUSTED, 404는 상품 → 쿠폰 — §E5
- `[DECISION]` 만료 시각이 지난 PENDING_PAYMENT의 취소는 409(상태 불변) — §E9
- `[DECISION]` totalPrice=0으로 결제된 주문의 환불은 PG를 생략한다 — §E9
- `[DECISION]` PG의 4xx·해석 불가 응답은 장애(503)로 취급 — §7
- `[DECISION]` 만료는 500ms 주기 스케줄러 단독, 주문별 트랜잭션 + SKIP LOCKED — §8
- `[DECISION]` 멱등 지문은 검증된 DTO 기준(의미 비교) — §6.3
- `[DECISION]` 415 → 400 VALIDATION_ERROR. 계약 밖 오류의 code — §11.2
- `[DECISION]` 빈 문자열 쿼리 파라미터는 400 — §E7
- `[LIMIT]` 남은 IN_PROGRESS 정리 없음(키 만료는 범위 밖). 결제 키 재사용 시 PG 결과가 엉킬 수 있음 — §6.6

---

## 16. 정합 요약 (구현·검증의 기준표)

### 16(a). 응답/요청 필드 ↔ 테이블 컬럼

**ProductResponse ↔ `products`**
| 필드 | Java(DTO) | 컬럼 | DB 타입 | NULL | 비고 |
|---|---|---|---|---|---|
| `id` | long | `id` | BIGINT IDENTITY | N | |
| `name` | String | `name` | VARCHAR(100) | N | 요청 필수 ↔ NOT NULL |
| `price` | long | `price` | BIGINT | N | CHECK 1..10,000,000 |
| `stock` | int | `stock` | INTEGER | N | 요청 0..1,000,000. CHECK ≥ 0 |
| `reserved` | int | `reserved` | INTEGER | N | DEFAULT 0. CHECK 0 ≤ reserved ≤ stock |
| `available` | int | (없음) | — | — | `stock − reserved` 계산값 |
| (노출 안 함) | | `created_at` | TIMESTAMPTZ | N | |

**CouponResponse ↔ `coupons`**
| 필드 | Java(DTO) | 컬럼 | DB 타입 | NULL | 비고 |
|---|---|---|---|---|---|
| `code` | String | `code` | VARCHAR(20) | N | UNIQUE `uk_coupons_code`, CHECK 정규식 |
| `type` | String(enum 이름) | `discount_type` | VARCHAR(10) | N | 컬럼명 다름(예약어 회피). CHECK FIXED/RATE |
| `value` | long | `discount_value` | BIGINT | N | 컬럼명 다름. CHECK ≥1, RATE면 ≤100 |
| `minOrderAmount` | long | `min_order_amount` | BIGINT | N | 요청 선택 → 앱이 0으로 채움(DEFAULT 0) |
| `maxDiscountAmount` | Long(null 가능) | `max_discount_amount` | BIGINT | **Y** | 요청 선택 ↔ nullable. null = 무제한 |
| `totalQuantity` | long | `total_quantity` | BIGINT | N | CHECK ≥ 1 |
| `usedCount` | long | `used_count` | BIGINT | N | DEFAULT 0. CHECK 0..total_quantity |
| `validFrom` | OffsetDateTime(UTC) | `valid_from` | TIMESTAMPTZ | N | |
| `validUntil` | OffsetDateTime(UTC) | `valid_until` | TIMESTAMPTZ | N | CHECK valid_from < valid_until |
| (노출 안 함) | | `id`, `created_at` | | | |

**OrderResponse ↔ `orders` / `order_items`**
| 필드 | Java(DTO) | 컬럼 | DB 타입 | NULL | 비고 |
|---|---|---|---|---|---|
| `id` | long | `orders.id` | BIGINT IDENTITY | N | |
| `userId` | String | `orders.user_id` | VARCHAR(50) | N | 헤더 `X-User-Id` 필수 ↔ NOT NULL |
| `status` | String(enum 이름) | `orders.status` | VARCHAR(20) | N | CHECK 8개 값 |
| `items[]` | List | `order_items` (ORDER BY `line_no`) | | | PK (order_id, line_no) |
| `items[].productId` | long | `order_items.product_id` | BIGINT FK→products | N | UNIQUE (order_id, product_id) ↔ 중복 400 |
| `items[].quantity` | int | `order_items.quantity` | INTEGER | N | CHECK 1..1000 |
| `items[].unitPrice` | long | `order_items.unit_price` | BIGINT | N | 주문 시점 products.price 복사 |
| `couponCode` | String(null 가능) | `orders.coupon_code` | VARCHAR(20) FK→coupons(code) | **Y** | 요청 선택 ↔ nullable |
| `subtotal` | long | `orders.subtotal` | BIGINT | N | |
| `discount` | long | `orders.discount` | BIGINT | N | CHECK 0..subtotal. 쿠폰 없으면 0 |
| `totalPrice` | long | `orders.total_price` | BIGINT | N | CHECK = subtotal − discount |
| `createdAt` | OffsetDateTime(UTC) | `orders.created_at` | TIMESTAMPTZ | N | 불변, µs |
| `expiresAt` | OffsetDateTime(UTC) | `orders.expires_at` | TIMESTAMPTZ | N | 불변, created_at + TTL |
| `paidAt` | OffsetDateTime(null 가능) | `orders.paid_at` | TIMESTAMPTZ | **Y** | CHECK: PAID·SHIPPED·DELIVERED·REFUNDED ⇔ NOT NULL |
| (노출 안 함) | | `orders.payment_id` | VARCHAR(255) | Y | PG paymentId. 환불에 사용 |
| (노출 안 함) | | `orders.updated_at` | TIMESTAMPTZ | N | |

**OrderPageResponse**
| 필드 | 원천 |
|---|---|
| `content[]` | `orders` (+ `order_items`), 인덱스 `ix_orders_*_created_id` |
| `nextCursor` | Base64URL(`epochMicros(created_at)` + ":" + `id`) of 마지막 원소. 없으면 null |

**헤더 ↔ 컬럼**
| 헤더 | 컬럼 | 비고 |
|---|---|---|
| `X-User-Id` (E5) | `orders.user_id` VARCHAR(50) | 1..50자 ↔ 길이 50 |
| `Idempotency-Key` (E5, E8) | `idempotency_records.idempotency_key` VARCHAR(64) | 1..64자 ↔ 길이 64. E8에서는 PG `Idempotency-Key`로도 그대로 전달 |
| (E5·E8 지문) | `idempotency_records.request_fingerprint` VARCHAR(64) | SHA-256 hex |

### 16(b). DB 제약 / 도메인 규칙 ↔ HTTP 상태·code

| 규칙/제약 | 판정 위치 | 상태 | code |
|---|---|---|---|
| 요청 필드 범위·형식(R1.2, R2.2, R3.2), 헤더, 쿼리, JSON 파싱·타입 | Bean Validation / Jackson / 컨트롤러 (DB CHECK는 안전망) | 400 | `VALIDATION_ERROR` |
| `order_items` UNIQUE (order_id, product_id) | 앱이 400으로 선판정 | 400 | `VALIDATION_ERROR` |
| `idempotency_records` UNIQUE (scope, idempotency_key) + 지문 불일치 | IdempotencyService (`ON CONFLICT`) | 422 | `IDEMPOTENCY_KEY_MISMATCH` |
| 같은 키 IN_PROGRESS | IdempotencyService | 409 | `IDEMPOTENCY_IN_PROGRESS` |
| `products.id` 미존재 (GET, 주문 항목) | 서비스 | 404 | `PRODUCT_NOT_FOUND` |
| `coupons.code` 미존재 (GET, 주문 couponCode) | 서비스 | 404 | `COUPON_NOT_FOUND` |
| `orders.id` 미존재 | 서비스 | 404 | `ORDER_NOT_FOUND` |
| `available < quantity` / CHECK `reserved <= stock` | 서비스(락 후) / DB 안전망 | 409 | `INSUFFICIENT_STOCK` |
| 유효기간·minOrderAmount·사용자 중복 사용 / 부분 유니크 `ux_orders_active_coupon_user` | 서비스(락 후) / DB 안전망 | 409 | `COUPON_NOT_APPLICABLE` |
| `used_count = total_quantity` / CHECK `used_count <= total_quantity` | 서비스(락 후) / DB 안전망 | 409 | `COUPON_EXHAUSTED` |
| UNIQUE `uk_coupons_code` | 서비스 선확인 + DB 최종 | 409 | `DUPLICATE_COUPON_CODE` |
| 상태 전이 위반 (§13), 결제 시 `now ≥ expires_at` | 서비스(주문 락 후) | 409 | `INVALID_STATE` |
| PG `DECLINED` | 결제 서비스 | 402 | `PAYMENT_DECLINED` |
| PG 5xx / 연결 실패 / 2초 초과 / 해석 불가 | PaymentGatewayClient | 503 | `PAYMENT_GATEWAY_UNAVAILABLE` |
| 그 밖의 CHECK 위반(`ck_orders_amounts`, `ck_orders_paid_at` 등) | DB | 500 | `INTERNAL_ERROR` (버그 신호. 정상 경로에서는 발생하지 않아야 함) |
