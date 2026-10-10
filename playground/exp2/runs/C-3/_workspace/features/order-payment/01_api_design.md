# 01. API 설계: order-payment

- 근거: `feature.md`(R1~R11, C1~C4, 외부 PG 계약), `00_requirements.json`
- 스택: Java 21 / Spring Boot 3.5.16 / Spring Data JPA (Hibernate 6) / PostgreSQL / Flyway / JUnit 5 + Testcontainers
- 데이터 모델: [02_db_design.md](./02_db_design.md). 필드와 컬럼 대응, 오류와 상태코드 매핑은 이 문서 끝의 **§16 정합 요약**만을 기준으로 삼는다.
- 기존 코드: `com.example.order.OrderApplication` 하나와 `application.yml`(앱 이름만 있음)뿐이다. 따를 기존 관례가 없으므로 아래 내용이 새 기본값이다.

---

## 0. 패키지 구조 (기능별)

베이스 패키지는 `com.example.order`다(기존 `OrderApplication` 위치). 주문 기능 패키지는 `orders`로 이름 짓는다. `order.order`가 되는 것을 피하기 위해서다.

```
com.example.order
├── OrderApplication.java              # @SpringBootApplication @EnableScheduling @ConfigurationPropertiesScan
├── common/
│   ├── config/ClockConfig.java        # @Bean Clock clock() = Clock.systemUTC()
│   ├── config/PaymentGatewayConfig.java   # RestClient 빈(§12)
│   ├── error/ErrorCode.java           # enum: code → HttpStatus (§2.3)
│   ├── error/BusinessException.java   # RuntimeException(ErrorCode, String detail)
│   ├── error/GlobalExceptionHandler.java  # @RestControllerAdvice extends ResponseEntityExceptionHandler
│   └── time/Times.java                # now(Clock) = clock.instant().truncatedTo(MICROS)
├── product/   Product, ProductRepository, ProductService, ProductController, dto/{CreateProductRequest, ProductResponse}
├── coupon/    Coupon, CouponType, CouponRepository, CouponService, CouponController, DiscountCalculator, dto/{CreateCouponRequest, CouponResponse}
├── orders/    Order, OrderItem, OrderStatus, OrderRepository, OrderQueryRepository(+Impl),
│              OrderService(@Transactional 비즈니스), OrderFacade(멱등 오케스트레이션·트랜잭션 없음),
│              PaymentService(@Transactional 결제), OrderExpirySweeper, CursorCodec,
│              OrderProperties, dto/{CreateOrderRequest, OrderItemRequest, PayRequest, OrderResponse, OrderItemResponse, OrderPageResponse}
├── payment/   PaymentGatewayClient, PaymentGatewayProperties, PgPaymentResult, PgPaymentStatus,
│              PaymentGatewayUnavailableException
└── idempotency/ IdempotencyStore(JdbcTemplate), IdempotencyScope, RequestHasher, StoredResponse
```

레이어 규칙은 `web → (facade) → service → repository` 단방향이다. 엔티티를 응답에 직접 노출하지 않고 record DTO로만 응답한다.

**OrderStatus (공개 상태, 정확히 8개)**: `PENDING_PAYMENT`, `PAID`, `SHIPPED`, `DELIVERED`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`. 명세 상태 다이어그램의 노드 전체다. `PAYING` 같은 내부 상태나 플래그는 **두지 않는다**. 결제 직렬화는 행 잠금으로 해결한다(§7).

---

## 1. 공통 규약

### 1.1 금액 (C1)
`price`, `unitPrice`, `subtotal`, `discount`, `totalPrice`, `value`, `minOrderAmount`, `maxDiscountAmount`, PG `amount`는 모두 **Java `long`/`Long`, JSON 정수, DB `bigint`**로 다룬다. 할인 계산에는 `Math.multiplyExact`를 쓴다. 최대 subtotal은 20 × 1,000 × 10,000,000 = 2×10^11이므로 `× 100`을 해도 long 범위 안이다.

### 1.2 시각 (C2)
- 응답의 시각 필드는 **`java.time.Instant`**이다. Spring Boot 기본 Jackson(`WRITE_DATES_AS_TIMESTAMPS=false`)이 `"2026-10-10T01:02:03.123456Z"` 형태로 직렬화하며, `Z`가 오프셋 표기다.
- 요청의 시각 필드(쿠폰 `validFrom`, `validUntil`)는 **`java.time.OffsetDateTime`**으로 받아 `toInstant()`로 저장한다. 오프셋이 없는 문자열(`"2026-01-01T00:00:00"`)은 역직렬화 실패로 400 `VALIDATION_ERROR`가 된다. 응답에서는 UTC(`Z`)로 정규화된 값이 나간다. 같은 순간이고 표기만 다르다.
- **정밀도**: 모든 "현재 시각"은 `clock.instant().truncatedTo(ChronoUnit.MICROS)`로 얻는다(`Times.now(clock)`). PostgreSQL `timestamptz`는 마이크로초 정밀도에서 **반올림**하므로, 앱에서 미리 절사하지 않으면 메모리 값, DB 값, 커서 값이 서로 어긋난다(R9 커서 동등 비교가 깨진다). `expiresAt = createdAt + ttl` 계산 결과도 다시 절사한다.
- 시각 소스는 `Clock` 빈(`Clock.systemUTC()`)을 주입받는다. `Instant.now()`를 직접 호출하지 않는다.

### 1.3 헤더
| 헤더 | 사용처 | 규칙 | 위반 |
|---|---|---|---|
| `X-User-Id` | `POST /api/orders` (필수) | `@NotBlank @Size(max = 50)` → 공백 아닌 1~50자 | 누락이나 위반은 400 |
| `X-User-Id` | `POST /api/orders/{id}/pay` (선택) | 검증하지 않음. 있으면 멱등 해시에 포함(§5.3) | - |
| `Idempotency-Key` | `POST /api/orders`, `POST /api/orders/{id}/pay` (필수) | `@NotBlank @Size(max = 64)` | 누락이나 위반은 400 |

- 헤더 검증은 컨트롤러 메서드 파라미터에 `@RequestHeader("X-User-Id") @NotBlank @Size(max=50) String userId`처럼 **제약 애노테이션을 직접** 붙여 Spring MVC 6.1+ 내장 메서드 검증이 처리하게 한다. 실패하면 `HandlerMethodValidationException`이 난다.
  - **컨트롤러 클래스에 `@Validated`를 붙이지 않는다.** 붙이면 AOP 기반 검증으로 바뀌어 `ConstraintViolationException`이 발생한다. 핸들러가 이 예외도 400으로 매핑하므로 결과는 같지만, 경로를 하나로 유지하기 위해 붙이지 않는다.
- 헤더가 누락되면 `MissingRequestHeaderException`이 나고 400이 된다.

### 1.4 Location
201 응답의 `Location`은 **상대 경로**다. `/api/products/{id}`, `/api/coupons/{code}`, `/api/orders/{id}` 형태로 `URI.create(...)`로 만든다. 멱등 재생 때 같은 값을 그대로 돌려주기 위해 호스트에 의존하지 않게 했다.

### 1.5 null 직렬화
응답 DTO의 nullable 필드(`couponCode`, `paidAt`, `maxDiscountAmount`, `nextCursor`)는 **생략하지 않고 `null`로 직렬화**한다. Spring Boot 기본 설정(inclusion ALWAYS)을 유지하며, `@JsonInclude(NON_NULL)`이나 `spring.jackson.default-property-inclusion`을 쓰지 않는다.

### 1.6 Jackson 설정
- `spring.jackson.deserialization.accept-float-as-int: false`: 정수 필드에 `1.5`가 오면 절사하지 않고 400으로 처리한다.
- int나 long 범위를 넘는 숫자, 잘못된 enum 문자열, 잘못된 JSON 문법은 모두 `HttpMessageNotReadableException`이 되어 400 `VALIDATION_ERROR`로 응답한다.
- 알 수 없는 필드는 무시한다(Boot 기본 `fail-on-unknown-properties=false` 유지).

---

## 2. 에러 포맷 (R11)

### 2.1 형식
모든 오류는 RFC 9457 Problem Details이고 **`Content-Type: application/problem+json`**이다.

```json
{
  "type": "https://example.com/problems/insufficient-stock",
  "title": "Conflict",
  "status": 409,
  "detail": "재고가 부족합니다: productId=3",
  "code": "INSUFFICIENT_STOCK",
  "instance": "/api/orders"
}
```

| 필드 | 값 |
|---|---|
| `type` | `https://example.com/problems/{code를 소문자 kebab-case로}` (예: `VALIDATION_ERROR` → `validation-error`) |
| `title` | HTTP reason phrase (`Bad Request`, `Payment Required`, `Not Found`, `Conflict`, `Unprocessable Entity`, `Service Unavailable`) |
| `status` | HTTP 상태 정수 |
| `detail` | 사람이 읽는 메시지. 500에는 내부 정보를 넣지 않는다. |
| `code` | §2.3 표의 값 (`ProblemDetail.setProperty("code", ...)`) |
| `errors` | (400 검증에서만, 선택) `[{ "field": "items[0].quantity", "message": "..." }]` |

### 2.2 Content-Type 보장 방법
`@ExceptionHandler`가 `ProblemDetail`을 그냥 반환하면, 클라이언트가 `Accept: application/json`을 보낼 때 `application/json`으로 협상될 수 있다. 그래서 **항상 `ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)`로 Content-Type을 미리 지정해** 내보낸다. `ResponseEntityExceptionHandler`를 상속해 처리하는 프레임워크 예외도 `handleExceptionInternal(...)`을 오버라이드해 `headers.setContentType(APPLICATION_PROBLEM_JSON)`을 넣고 `code`와 `type`을 채운다.

### 2.3 ErrorCode (R11.3)

| code | HTTP | 발생 위치 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 본문·헤더·쿼리·경로 검증 실패, JSON 파싱 실패, 커서 해석 실패 |
| `PAYMENT_DECLINED` | 402 | PG가 `DECLINED` 반환 |
| `PRODUCT_NOT_FOUND` | 404 | 상품 조회, 주문 생성 시 없는 productId |
| `COUPON_NOT_FOUND` | 404 | 쿠폰 조회, 주문 생성 시 없는 couponCode |
| `ORDER_NOT_FOUND` | 404 | 주문 조회·pay·cancel·ship·deliver |
| `INSUFFICIENT_STOCK` | 409 | 주문 생성 시 `available` 부족 |
| `COUPON_NOT_APPLICABLE` | 409 | 유효기간 밖, `subtotal < minOrderAmount`, 같은 사용자가 사용 중인 주문 존재 |
| `COUPON_EXHAUSTED` | 409 | `usedCount = totalQuantity` |
| `DUPLICATE_COUPON_CODE` | 409 | 쿠폰 등록 시 code 중복 |
| `INVALID_STATE` | 409 | 상태 전이 불가, 결제·취소 시점에 이미 만료됨 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | 같은 키의 최초 요청이 아직 처리 중 |
| `IDEMPOTENCY_KEY_MISMATCH` | 422 | 같은 키로 다른 요청이 옴 |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 503 | PG 5xx, 연결 실패, 2초 초과, 계약 밖 응답 |

**R1~R10이 정의하지 않은 프레임워크 오류**(명세 범위 밖, "가능하면 problem+json")도 같은 형식으로 응답하며, code는 다음 확장값을 쓴다.
- 404 미매핑 경로(`NoResourceFoundException`): `NOT_FOUND`
- 405: `METHOD_NOT_ALLOWED`
- 406: `NOT_ACCEPTABLE`
- 415: `UNSUPPORTED_MEDIA_TYPE`
- 그 외 `Exception`: 500 `INTERNAL_ERROR`

명세가 정의한 오류에는 확장 code를 쓰지 않는다.

### 2.4 400 VALIDATION_ERROR로 매핑하는 예외 (전부)
| 예외 | 원인 |
|---|---|
| `MethodArgumentNotValidException` | `@Valid @RequestBody` 실패 |
| `HandlerMethodValidationException` | 헤더·쿼리 파라미터 제약 실패. 메서드 검증이 적용되면 본문 실패도 여기로 온다. |
| `jakarta.validation.ConstraintViolationException` | 방어용 |
| `MissingRequestHeaderException` (`ServletRequestBindingException`) | `X-User-Id`나 `Idempotency-Key` 누락 |
| `MissingServletRequestParameterException` | 방어용 |
| `MethodArgumentTypeMismatchException` / `TypeMismatchException` | `/api/orders/abc`, `?size=abc` |
| `HttpMessageNotReadableException` | 본문 없음, JSON 문법 오류, 타입·범위 불일치, enum 불일치, 오프셋 없는 시각 |
| `BusinessException(VALIDATION_ERROR)` | 커서 해석 실패, 정의되지 않은 status 등 앱 내부 검증 |

`ResponseEntityExceptionHandler`가 처리하는 예외는 `handleExceptionInternal` 오버라이드에서 상태가 400이면 code를 `VALIDATION_ERROR`로 넣는다. 나머지는 `@ExceptionHandler`로 직접 처리한다.

### 2.5 DB 제약 위반 예외 매핑
`DataIntegrityViolationException`을 잡아 원인 체인의 `org.hibernate.exception.ConstraintViolationException#getConstraintName()`(또는 PSQLException의 `ServerErrorMessage#getConstraint()`)으로 분기한다. 제약 이름은 02 문서의 이름과 정확히 같아야 한다.
- `uq_coupons_code`: 409 `DUPLICATE_COUPON_CODE`
- `ux_orders_active_user_coupon`: 409 `COUPON_NOT_APPLICABLE`
- 그 외: 500 `INTERNAL_ERROR`. 정상 흐름에서는 발생하지 않아야 한다.

이 매핑은 서비스 안에서 `saveAndFlush`를 감싼 try/catch로 해서 `BusinessException`으로 바꿔 던지는 방식을 권장한다. 트랜잭션은 그대로 롤백된다.

---

## 3. 엔드포인트 요약

| # | 메서드·경로 | 성공 | 헤더 | 오류 (C3 순서) |
|---|---|---|---|---|
| E1 | `POST /api/products` | 201 + Location, `ProductResponse` | - | 400 |
| E2 | `GET /api/products/{id}` | 200 `ProductResponse` | - | 400(id 형식) → 404 `PRODUCT_NOT_FOUND` |
| E3 | `POST /api/coupons` | 201 + Location, `CouponResponse` | - | 400 → 409 `DUPLICATE_COUPON_CODE` |
| E4 | `GET /api/coupons/{code}` | 200 `CouponResponse` | - | 404 `COUPON_NOT_FOUND` |
| E5 | `POST /api/orders` | 201 + Location, `OrderResponse` | `X-User-Id`, `Idempotency-Key` | 400 → 422/409(멱등) → 404 PRODUCT → 404 COUPON → 409 STOCK → 409 COUPON_NOT_APPLICABLE → 409 COUPON_EXHAUSTED |
| E6 | `GET /api/orders/{id}` | 200 `OrderResponse` | - | 400(id) → 404 `ORDER_NOT_FOUND` |
| E7 | `GET /api/orders` | 200 `OrderPageResponse` | - | 400 |
| E8 | `POST /api/orders/{id}/pay` | 200 `OrderResponse` | `Idempotency-Key`, (`X-User-Id` 선택) | 400 → 422/409(멱등) → 404 → 409 `INVALID_STATE` → 402 / 503 |
| E9 | `POST /api/orders/{id}/cancel` | 200 `OrderResponse` | - | 400(id) → 404 → 409 `INVALID_STATE` → 503 |
| E10 | `POST /api/orders/{id}/ship` | 200 `OrderResponse` | - | 400(id) → 404 → 409 `INVALID_STATE` |
| E11 | `POST /api/orders/{id}/deliver` | 200 `OrderResponse` | - | 400(id) → 404 → 409 `INVALID_STATE` |

경로의 `{id}`는 `Long`이다. 숫자가 아니거나 long 범위를 넘으면 400이다. 0이나 음수는 존재하지 않는 리소스이므로 404로 처리한다.
요청 `Content-Type`은 `application/json`이다. E9~E11은 본문을 읽지 않으며, 본문이 와도 무시한다.

---

## 4. 엔드포인트 상세

### E1. `POST /api/products` (R1.1, R1.2)
요청 `CreateProductRequest`
| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| `name` | string | Y | `@NotBlank @Size(max=100)`. 저장할 때 trim하지 않는다. |
| `price` | integer(long) | Y | `@NotNull @Min(1) @Max(10_000_000)` |
| `stock` | integer(int) | Y | `@NotNull @Min(0) @Max(1_000_000)` |

응답은 201, `Location: /api/products/{id}`, 본문은 `ProductResponse`이고 `reserved`=0이다.

### E2. `GET /api/products/{id}` (R1.3, R1.4)
`ProductResponse`
| 필드 | 타입 | null | 비고 |
|---|---|---|---|
| `id` | long | N | |
| `name` | string | N | |
| `price` | long | N | |
| `stock` | int | N | 판매되지 않은 보유 수량 |
| `reserved` | int | N | 결제 대기 주문이 잡아 둔 수량 |
| `available` | int | N | `stock - reserved`. 계산값이며 컬럼이 없다. |

없으면 404 `PRODUCT_NOT_FOUND`.

### E3. `POST /api/coupons` (R2.1, R2.2)
요청 `CreateCouponRequest`
| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| `code` | string | Y | `@NotNull @Pattern("^[A-Z0-9]{4,20}$")` |
| `type` | string | Y | `@NotNull @Pattern("^(FIXED\|RATE)$")`. 통과하면 `CouponType.valueOf`로 변환한다. |
| `value` | long | Y | `@NotNull @Min(1)`. 교차 검증: `type=RATE`면 `value <= 100`이어야 한다(`@AssertTrue isRateValueInRange()`). |
| `minOrderAmount` | long | N | `@PositiveOrZero`. null이나 생략이면 0이다. |
| `maxDiscountAmount` | long | N | `@Min(1)`. null이나 생략이면 제한이 없고 null로 저장한다. |
| `totalQuantity` | long | Y | `@NotNull @Min(1)` |
| `validFrom` | OffsetDateTime | Y | `@NotNull`, 오프셋 필수 |
| `validUntil` | OffsetDateTime | Y | `@NotNull`. 교차 검증 `validFrom < validUntil` (`@AssertTrue isValidPeriod()`) |

- `@AssertTrue` 메서드는 피연산자가 null이면 `true`를 반환한다(null은 `@NotNull`이 따로 잡는다). Jackson 직렬화에 끼지 않도록 `@JsonIgnore`를 붙인다.
- 처리 순서:
  1. 400
  2. `existsByCode`가 참이면 409 `DUPLICATE_COUPON_CODE`
  3. `saveAndFlush`. 동시 등록 경합으로 `uq_coupons_code` 위반이 나면 409 `DUPLICATE_COUPON_CODE`
- 응답은 201, `Location: /api/coupons/{code}`, 본문은 `CouponResponse`이고 `usedCount`=0이다.

### E4. `GET /api/coupons/{code}` (R2.3)
`CouponResponse`
| 필드 | 타입 | null |
|---|---|---|
| `code` | string | N |
| `type` | string `FIXED`\|`RATE` | N |
| `value` | long | N |
| `minOrderAmount` | long | N |
| `maxDiscountAmount` | long | **Y** |
| `totalQuantity` | long | N |
| `usedCount` | long | N |
| `validFrom` | string(ISO-8601, Z) | N |
| `validUntil` | string(ISO-8601, Z) | N |

code는 대소문자를 구분해 정확히 일치해야 한다. 없으면 404 `COUPON_NOT_FOUND`.

### E5. `POST /api/orders` (R3.1~R3.4, R4)
헤더는 `X-User-Id`(필수, 1~50, 공백만 불가)와 `Idempotency-Key`(필수, 1~64)다.

요청 `CreateOrderRequest`
| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| `items` | array | Y | `@NotNull @Size(min=1, max=20)` `List<@NotNull @Valid OrderItemRequest>` |
| `items[].productId` | long | Y | `@NotNull`. 존재 여부는 404 단계에서 본다. |
| `items[].quantity` | int | Y | `@NotNull @Min(1) @Max(1000)` |
| `couponCode` | string | N | null이나 생략이면 쿠폰 없음. 형식 검증은 하지 않고, 어떤 문자열이든 조회해서 없으면 404 `COUPON_NOT_FOUND`다. |

- 같은 `productId` 중복은 400이다. `CreateOrderRequest`의 `@AssertTrue @JsonIgnore boolean isProductIdsUnique()`로 Bean Validation 단계에서 검사해 **멱등 검사보다 먼저** 걸리게 한다(C3).
- 응답은 201, `Location: /api/orders/{id}`, 본문은 `OrderResponse`이고 `status=PENDING_PAYMENT`, `paidAt=null`이다.
- 처리 흐름은 §5(멱등)와 §6(생성 트랜잭션)을 따른다.

### E6. `GET /api/orders/{id}` (R3.5)
`OrderResponse`
| 필드 | 타입 | null | 비고 |
|---|---|---|---|
| `id` | long | N | |
| `userId` | string | N | |
| `status` | string | N | §0의 공개 상태 8개 중 하나 |
| `items` | array | N | `line_no` 오름차순, 즉 요청 순서 |
| `items[].productId` | long | N | |
| `items[].quantity` | int | N | |
| `items[].unitPrice` | long | N | 주문 시점 상품 가격 |
| `couponCode` | string | **Y** | |
| `subtotal` | long | N | Σ(unitPrice × quantity) |
| `discount` | long | N | R2.4 |
| `totalPrice` | long | N | subtotal − discount |
| `createdAt` | string(ISO-8601) | N | |
| `expiresAt` | string(ISO-8601) | N | createdAt + `order.payment-ttl` |
| `paidAt` | string(ISO-8601) | **Y** | 결제 승인 시각. 승인 전이거나 승인되지 않았으면 null. REFUNDED 이후에도 유지 |

조회는 읽기 전용 트랜잭션이다. 만료 반영은 스위퍼(§9)가 맡으며, 조회 시점에 상태를 고쳐 쓰지 않는다. 없으면 404 `ORDER_NOT_FOUND`.

### E7. `GET /api/orders` (R9)
쿼리 파라미터
| 이름 | 타입 | 기본 | 검증 |
|---|---|---|---|
| `userId` | string | 없음 | 선택. 빈 문자열은 생략으로 본다. 50자를 넘으면 400(`@Size(max=50)`). |
| `status` | string | 없음 | 선택. 빈 문자열은 생략으로 본다. 그 외에는 `OrderStatus` 8개 이름 중 하나와 **정확히(대소문자 구분)** 일치해야 하며, 아니면 400. |
| `size` | int | 20 | `@Min(1) @Max(100)`. 숫자가 아니면 400. |
| `cursor` | string | 없음 | 선택. 빈 문자열은 생략으로 본다. 해석에 실패하면 400(§10). |

응답 `OrderPageResponse`는 `{ "content": OrderResponse[], "nextCursor": string|null }`이다. 정렬, 커서, 쿼리는 §10을 따른다.

### E8. `POST /api/orders/{id}/pay` (R5, R4)
헤더는 `Idempotency-Key`(필수)와 `X-User-Id`(선택, 검증 없음)다.
요청 `PayRequest`는 `{ "cardToken": string }`이며 `@NotBlank`다. 길이 제한은 없고 저장하지 않으며 PG에 그대로 전달한다.
응답은 200 `OrderResponse`(`status=PAID`, `paidAt` 채움)다. 오류는 400 → 422/409(멱등) → 404 `ORDER_NOT_FOUND` → 409 `INVALID_STATE` → 402 `PAYMENT_DECLINED` / 503 `PAYMENT_GATEWAY_UNAVAILABLE` 순서다. 흐름은 §7을 따른다.

### E9. `POST /api/orders/{id}/cancel` (R7)
응답은 200 `OrderResponse`다.
- `PENDING_PAYMENT`이고 `now < expiresAt`이면 `CANCELLED`로 바꾸고 예약과 쿠폰 사용을 복원한다.
- `PAID`이면 PG에 환불을 요청한다. 성공하면 `REFUNDED`가 되고 재고 증가와 쿠폰 복원이 일어난다. PG 장애면 503이고 아무것도 바뀌지 않는다.
- `PENDING_PAYMENT`인데 `now >= expiresAt`이면(스위퍼가 아직 처리하지 않은 만료 주문) 409 `INVALID_STATE`다. 상태는 바꾸지 않고 스위퍼가 곧 `EXPIRED`로 만든다.
- 그 밖의 상태(`SHIPPED`, `DELIVERED`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`)는 409 `INVALID_STATE`다.

흐름은 §8을 따른다.

### E10, E11. `POST /api/orders/{id}/ship` · `/deliver` (R8)
- ship은 `PAID`를 `SHIPPED`로, deliver는 `SHIPPED`를 `DELIVERED`로 바꾼다. 응답은 200 `OrderResponse`다.
- 그 밖의 상태는 409 `INVALID_STATE`, 없으면 404다.
- 주문 행에 `SELECT ... FOR UPDATE`를 건 뒤 상태를 검사하고 바꾼다(§8).

---

## 5. 멱등성 (R4): E5, E8 공통

### 5.1 저장소
`idempotency_keys` 테이블을 쓴다(02 문서). 유니크 키는 `(scope, idem_key)`다. `scope`가 `ORDER_CREATE`와 `ORDER_PAY`로 갈리므로 **두 엔드포인트의 키 공간이 서로 독립**이다(R4.1). 접근은 `IdempotencyStore`가 **`JdbcTemplate`**으로 한다. 엔티티는 두지 않는다. `JdbcTemplate`은 JPA 트랜잭션이 열려 있으면 그 커넥션에 참여하고, 없으면 자체 트랜잭션으로 실행된다.

### 5.2 처리 순서와 트랜잭션 경계
`OrderFacade`는 **트랜잭션이 없는** 빈이다. 다음 순서로 오케스트레이션한다.

```
[컨트롤러 진입 전] Bean Validation / 헤더 / 타입 변환에서 400 → 여기서 끝 (C3: 400이 멱등 검사보다 먼저)

1. hash = RequestHasher.hash(scope, userIdOrEmpty, "POST", path, requestDto)
2. begin = IdempotencyStore.begin(scope, key, hash)            ── 별도 트랜잭션(REQUIRES_NEW)으로 즉시 커밋
     INSERT INTO idempotency_keys(scope, idem_key, request_hash, status)
       VALUES (?, ?, ?, 'IN_PROGRESS')
     ON CONFLICT (scope, idem_key) DO NOTHING
     RETURNING id
   ├ 행이 반환됨 → 선점 성공(owner), recordId 확보 → 3으로
   └ 행 없음(충돌) → SELECT request_hash, status, response_status, response_body, response_location
                     FROM idempotency_keys WHERE scope=? AND idem_key=?
        ├ 조회 결과 없음(그 사이 owner가 실패해 삭제) → 2를 다시 시도(최대 3회, 그래도 없으면 409 IDEMPOTENCY_IN_PROGRESS)
        ├ request_hash ≠ hash          → 422 IDEMPOTENCY_KEY_MISMATCH   (해시 비교가 상태 확인보다 먼저)
        ├ status = IN_PROGRESS          → 409 IDEMPOTENCY_IN_PROGRESS
        └ status = COMPLETED            → 저장된 (response_status, response_body, response_location) 그대로 재생
3. try {
     stored = 비즈니스 서비스(@Transactional) 실행
              └ 성공 시 같은 트랜잭션 안에서 IdempotencyStore.complete(recordId, status, bodyJson, location)
                 UPDATE idempotency_keys SET status='COMPLETED', response_status=?, response_body=?,
                        response_location=?, completed_at=now() WHERE id=? AND status='IN_PROGRESS'
     return stored
   } catch (RuntimeException e) {                  // 4xx/5xx 전부
     IdempotencyStore.release(recordId)            ── 비즈니스 트랜잭션이 끝난 뒤 별도 트랜잭션
        DELETE FROM idempotency_keys WHERE id=? AND status='IN_PROGRESS'
     throw e
   }
   (결제 거절은 서비스가 예외 대신 Declined 결과를 반환하고 커밋한다. 퍼사드가 release 후 402를 던진다. §7)
```

핵심 결정:
- **선점 INSERT는 비즈니스 트랜잭션과 분리된 트랜잭션에서 즉시 커밋한다.** 그래야 동시에 들어온 같은 키 요청이 `ON CONFLICT`에서 충돌을 보고 `IN_PROGRESS`를 읽을 수 있다(R4.5). 선점을 비즈니스 트랜잭션 안에 두면, 두 번째 요청이 첫 트랜잭션 종료까지 INSERT에서 블록된다. 또 첫 요청이 실패하면 두 번째가 그대로 처리되어 버린다. 이렇게 되면 409 경로가 사라지고 커넥션 점유 시간도 길어진다.
- `begin`과 `release`는 `TransactionTemplate(PROPAGATION_REQUIRES_NEW)`로 감싼다. 퍼사드에는 트랜잭션이 없으므로 실제로는 "새 트랜잭션"이 된다. **비즈니스 트랜잭션 안에서 REQUIRES_NEW를 부르지 않는다.** 한 요청이 커넥션 두 개를 동시에 잡으면 풀 고갈 교착이 생길 수 있다.
- **완료 기록(complete)은 비즈니스 트랜잭션 안에서 한다.** 주문 생성이나 결제 반영과 `COMPLETED` 기록이 원자적으로 커밋되므로 "주문은 생겼는데 키는 IN_PROGRESS로 남는" 창이 없다.
- **오류로 끝나면 키를 삭제한다**(R4.4). 같은 요청으로 다시 시도하면 처음부터 다시 처리된다. 402(결제 거절)도 오류이므로 삭제한다. 이때 주문은 `PAYMENT_FAILED`가 되어 있으므로, 같은 키로 재시도하면 409 `INVALID_STATE`가 나온다. 의도된 동작이다.
- 2xx만 저장한다. 저장되는 상태는 E5가 201, E8이 200뿐이다.
- 키 만료와 청소는 범위 밖이다.

### 5.3 "같은 요청"의 정의와 해시 (R4.2)
```
canonical = scope + "\n"
          + (X-User-Id 헤더 값, 없으면 "") + "\n"
          + "POST " + path + "\n"
          + canonicalJson(requestDto)
hash      = SHA-256(canonical, UTF-8) → 소문자 hex 64자
```
- `path`
  - E5: `"/api/orders"`
  - E8: `"/api/orders/" + orderId + "/pay"`. 파싱된 long id로 만들므로, **같은 키라도 다른 주문 id면 다른 요청이고 422**다.
- `canonicalJson`은 Spring의 `ObjectMapper`를 `copy()`한 뒤 `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`를 켜서 **검증을 통과한 요청 DTO**를 직렬화한 값이다.
  - 공백이나 필드 순서가 다른 원문은 같은 요청으로 본다.
  - `items` **배열 순서는 보존**한다. `[P,Q]`와 `[Q,P]`는 다른 요청이다.
  - `couponCode`가 생략된 경우와 `null`인 경우는 같은 요청이다.
  - `@JsonIgnore`가 붙은 검증용 메서드는 해시에 들어가지 않는다.
- 사용자 구분은 해시에 포함된다. 같은 키를 다른 `X-User-Id`로 보내면 422다. 키 공간은 사용자별이 아니라 **scope 전역**이다.

### 5.4 재생 응답
- 퍼사드는 원 처리든 재생이든 `StoredResponse(int status, String bodyJson, String location)`를 반환한다.
- 컨트롤러는 이를 `ResponseEntity.status(status).contentType(APPLICATION_JSON).header(LOCATION, location?).body(bodyJson)`로 그대로 쓴다. `String` 본문은 `StringHttpMessageConverter`가 원문 그대로 출력한다.
- 원 처리 때도 같은 문자열을 응답하므로 **최초 응답과 재생 응답은 바이트 단위로 같다**.
- `bodyJson`은 서비스가 트랜잭션 안에서 Spring `ObjectMapper`로 `OrderResponse`를 직렬화한 값이다.

---

## 6. 주문 생성 트랜잭션과 동시성 (R3, R10.1~R10.4)

`OrderService.create(userId, request, idemRecordId)` 하나가 `@Transactional`(READ COMMITTED, PostgreSQL 기본)이다.

```
now = Times.now(clock)                                         // 요청 시각, 마이크로초 절사

① 404 상품   products = productRepository.findAllById(ids)    // 잠금 없음. price는 불변(수정 기능 없음)
             요청 items 순서대로 보며 없는 첫 productId → 404 PRODUCT_NOT_FOUND
② 404 쿠폰   couponCode != null → couponRepository.findByCode(code) → 없으면 404 COUPON_NOT_FOUND
             (type/value/min/max/validFrom/validUntil은 불변. usedCount는 이 엔티티 값을 쓰지 않는다)
③ 금액 계산  subtotal = Σ price×qty, discount = DiscountCalculator(R2.4), total = subtotal − discount
④ 409 재고   items를 productId 오름차순으로 정렬해 하나씩:
               UPDATE products SET reserved = reserved + :qty
                WHERE id = :id AND stock - reserved >= :qty         → 영향 0행이면 409 INSUFFICIENT_STOCK(productId)
             ※ 예외를 던지면 트랜잭션이 롤백되어 앞서 늘린 reserved도 원복(R3.4)
⑤ 409 쿠폰   (쿠폰이 있을 때만)
             a. !(validFrom <= now < validUntil)            → 409 COUPON_NOT_APPLICABLE
             b. subtotal < minOrderAmount                   → 409 COUPON_NOT_APPLICABLE
             c. SELECT id FROM coupons WHERE id = :id FOR NO KEY UPDATE      // 쿠폰 행 잠금
             d. SELECT EXISTS(SELECT 1 FROM orders WHERE user_id=:u AND coupon_code=:c
                  AND status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED'))
                                                             → true면 409 COUPON_NOT_APPLICABLE
             e. UPDATE coupons SET used_count = used_count + 1
                 WHERE id = :id AND used_count < total_quantity   → 영향 0행이면 409 COUPON_EXHAUSTED
⑥ 저장       createdAt = now, expiresAt = trunc(now + order.payment-ttl), status = PENDING_PAYMENT
             order + items(line_no = 요청 순서 0..n-1, unit_price = 상품 price) saveAndFlush
             ux_orders_active_user_coupon 위반 → 409 COUPON_NOT_APPLICABLE (최후 방어선)
⑦ 응답 직렬화 → IdempotencyStore.complete(recordId, 201, json, "/api/orders/{id}")  (같은 트랜잭션)
```

결정 근거:
- **C3 순서**: 404(상품 → 쿠폰) → 409 재고 → 409 쿠폰. 쿠폰 409 안에서는 `COUPON_NOT_APPLICABLE`(a → b → d)를 `COUPON_EXHAUSTED`(e)보다 먼저 본다. 재고가 부족하면 ④에서 끝나므로 쿠폰 검사까지 가지 않는다.
- **R10.1 재고**: 조건부 UPDATE 한 문장이 검사와 증가를 원자적으로 처리한다. 같은 상품을 동시에 갱신하면 행 잠금으로 직렬화되고, 뒤 트랜잭션은 커밋된 최신 행으로 `WHERE`를 재평가한다(READ COMMITTED). 그래서 초과 예약이 없다. DB `CHECK (reserved <= stock)`가 최후 방어선이다.
- **R10.4 데드락 회피**: 상품 잠금 순서를 **productId 오름차순으로 고정**한다. 재고를 건드리는 모든 경로(생성, 결제 승인, 거절, 만료, 취소, 환불)가 같은 순서를 쓴다.
- **R10.2 쿠폰 소진**: ⓒ의 쿠폰 행 잠금으로 같은 쿠폰 사용 생성들이 직렬화되고, ⓔ의 조건부 UPDATE가 `used_count <= total_quantity`를 보장한다. DB `CHECK (used_count <= total_quantity)`가 최후 방어선이다.
- **R10.3 같은 사용자, 같은 쿠폰**:
  - ⓒ 잠금을 얻은 **뒤에** ⓓ를 실행한다. READ COMMITTED에서는 문장마다 새 스냅샷을 쓰므로, 앞서 잠금을 쥐고 커밋한 같은 사용자 주문이 ⓓ에서 보인다.
  - 최후 방어선은 DB 부분 유니크 인덱스 `ux_orders_active_user_coupon (user_id, coupon_code) WHERE coupon_code IS NOT NULL AND status IN (활성 4개)`다.
  - 결과적으로 5건 중 정확히 1건만 201이다.
- **전역 잠금 순서**: `[기존 주문 행] → products(id 오름차순) → coupon`.
  - 생성 경로는 기존 주문 행을 잠그지 않고 products → coupon 순서로 잠근다.
  - 전이 경로(§7~§9)는 order → products → coupon 순서로 잠근다.
  - 순서가 단조로우므로 순환 대기가 없다.
- **쿠폰 잠금 강도로 `FOR NO KEY UPDATE`를 쓰는 이유**: `orders.coupon_code` FK 검사는 `FOR KEY SHARE`를 잡는다. `FOR NO KEY UPDATE`는 이와 충돌하지 않는다. `FOR UPDATE`를 써도 정확성은 같다.
- **영속성 컨텍스트 주의**:
  - ①②에서 로드한 `Product`와 `Coupon` 엔티티는 카운터 값이 낡을 수 있다. 카운터는 **반드시 네이티브 조건부 UPDATE로만** 바꾼다.
  - 엔티티의 `stock`, `reserved`, `usedCount` 필드는 `@Column(updatable = false)`로 매핑한다. 이렇게 하면 더티 체킹이 벌크 UPDATE 결과를 덮어쓸 수 없다(02 §4).
  - `spring.jpa.open-in-view: false`는 **필수**다. OSIV가 켜져 있으면 한 요청 안의 여러 트랜잭션(멱등 begin, 비즈니스, release)이 같은 영속성 컨텍스트를 공유해 낡은 엔티티를 읽는다.

### 6.1 할인 계산 (R2.4), `DiscountCalculator`
```
d = (type == FIXED) ? value : Math.multiplyExact(subtotal, value) / 100   // long 정수 나눗셈 = floor (양수)
if (maxDiscountAmount != null) d = min(d, maxDiscountAmount)
d = min(d, subtotal)
totalPrice = subtotal - d
```
쿠폰이 없으면 `discount=0`, `totalPrice=subtotal`이다.

---

## 7. 결제 (R5, R10.5)

### 7.1 결정: 주문 행 비관적 잠금을 PG 호출 동안 유지
`PaymentService.pay(orderId, cardToken, idemKey, idemRecordId)` 하나가 `@Transactional`이다.

```
① order = orderRepository.findByIdForUpdate(id)       // @Lock(PESSIMISTIC_WRITE) → SELECT ... FOR UPDATE
           없으면 404 ORDER_NOT_FOUND
② now = Times.now(clock)
   order.status != PENDING_PAYMENT  || !now.isBefore(order.expiresAt)  → 409 INVALID_STATE
③ if order.totalPrice == 0 → PG 호출 없이 승인 처리(⑤), paymentId = null      (R5.7)
   else result = pgClient.pay(orderId, totalPrice, cardToken, idemKey)          (R5.3)
        PaymentGatewayUnavailableException → 그대로 전파 → 트랜잭션 롤백 → 503   (R5.6: 아무것도 안 바뀜)
④ result.status == DECLINED:
     order.status = PAYMENT_FAILED, order.paymentId = result.paymentId
     items productId 오름차순: UPDATE products SET reserved = reserved - :qty WHERE id = :id
     couponCode != null: UPDATE coupons SET used_count = used_count - 1 WHERE code = :code AND used_count > 0
     return PayOutcome.Declined   ← 예외를 던지지 않는다(던지면 PAYMENT_FAILED 전이가 롤백됨). 커밋됨.
⑤ APPROVED (또는 total=0):
     paidAt = Times.now(clock), order.status = PAID, order.paymentId = result.paymentId(or null)
     items productId 오름차순: UPDATE products SET stock = stock - :qty, reserved = reserved - :qty WHERE id = :id
     json = serialize(OrderResponse) ; IdempotencyStore.complete(recordId, 200, json, null)
     return PayOutcome.Approved(StoredResponse)
```
`OrderFacade.pay`는 다음과 같이 처리한다.
- `Declined`를 받으면 `release(recordId)` 후 `BusinessException(PAYMENT_DECLINED)`을 던져 402로 응답한다. 서비스 트랜잭션은 이미 커밋된 상태다.
- 예외가 나면 `release(recordId)` 후 다시 던진다.

결정 근거:
- **R10.5 PG 호출 1회 이하**: 같은 주문에 대한 두 번째 결제 트랜잭션은 ①에서 첫 트랜잭션이 끝날 때까지 블록된다. 첫 트랜잭션이 커밋되면 두 번째는 갱신된 행을 읽어 `PAID`(또는 `PAYMENT_FAILED`)를 보고 ②에서 409를 반환한다. 따라서 PG 결제 요청은 1번, 성공 응답은 1건이다.
  - 첫 결제가 503으로 롤백된 경우에는 다음 대기자가 PG를 다시 호출할 수 있다. PG 장애 상황의 정상적인 재시도이며, R10.5가 전제하는 정상 PG 상황에는 해당하지 않는다.
- **공개 상태는 명세의 8개만 쓴다.** `PAYING` 같은 내부 상태나 플래그 컬럼이 없다. 잠금 플래그 방식은 장애 시 플래그가 남아 만료가 막히는 문제(리스 만료 로직 필요)가 있어 채택하지 않았다.
- **PG 장애 시 불변(R5.6)**: 잠금 외에는 PG 응답 전까지 쓰기가 없고, 예외가 나면 전체가 롤백된다.
- **결제와 만료의 경합**: 스위퍼는 `FOR UPDATE SKIP LOCKED`로 잠긴 주문을 건너뛴다(§9). 그래서 결제 중인 주문을 만료시키지 않고 블록되지도 않는다. 결제 트랜잭션이 ②에서 `now < expiresAt`을 확인했다면, PG 응답이 `expiresAt` 이후에 도착하더라도 **결제가 우선**한다. 만료 판정 시각은 잠금 획득 시점이다.
- **커넥션 점유**: 결제 트랜잭션은 PG 호출 동안(최대 약 2초) 커넥션 1개를 잡는다. 동시 결제 대기자도 각각 1개씩 잡으므로 Hikari `maximum-pool-size: 20`으로 둔다(§13).
- `paymentId`는 `orders.payment_id`에 저장한다. 환불할 때 쓰며 응답에는 노출하지 않는다.

---

## 8. 취소·환불·배송 (R7, R8)

`OrderService.cancel(id)`(`@Transactional`)는 다음과 같다.
```
order = findByIdForUpdate(id) → 없으면 404
now = Times.now(clock)
switch (order.status):
  PENDING_PAYMENT:
     now >= expiresAt → 409 INVALID_STATE (스위퍼에 맡김)
     status = CANCELLED; products 오름차순 reserved -= qty; coupon used_count -= 1
  PAID:
     paymentId != null → pgClient.refund(paymentId)   // 장애 → 예외 전파 → 롤백 → 503 (R7.3)
     (paymentId == null 즉 totalPrice 0 결제 → PG 호출 없이 환불 처리)
     status = REFUNDED; products 오름차순 stock += qty; coupon used_count -= 1
  그 외 → 409 INVALID_STATE
return OrderResponse
```
- 환불도 PG 호출 동안 주문 행 잠금을 유지한다. 동시 취소가 와도 PG 환불 요청은 1번이고, 나중 요청은 `REFUNDED`를 보고 409를 받는다.
- `REFUNDED` 전이 시 `paidAt`은 유지한다(null로 바꾸지 않는다).

`ship`, `deliver`는 다음과 같다.
```
order = findByIdForUpdate(id) → 없으면 404
ship:    status == PAID    → SHIPPED   / 아니면 409 INVALID_STATE
deliver: status == SHIPPED → DELIVERED / 아니면 409 INVALID_STATE
```

### 8.1 쿠폰 사용 복원 규칙 (R2.6)
주문이 `CANCELLED`, `EXPIRED`, `PAYMENT_FAILED`, `REFUNDED`가 되는 **모든 경로**에서 `coupon_code`가 있으면 `used_count - 1`을 실행한다. 그러면 부분 유니크 인덱스의 대상에서도 빠지므로, 같은 사용자가 다시 사용할 수 있다.

### 8.2 재고 변화 요약
| 전이 | products 변경 |
|---|---|
| 생성 (→ PENDING_PAYMENT) | `reserved += q` |
| 결제 승인 (→ PAID) | `stock -= q`, `reserved -= q` |
| 거절·만료·취소 (PENDING_PAYMENT → PAYMENT_FAILED·EXPIRED·CANCELLED) | `reserved -= q` |
| 환불 (PAID → REFUNDED) | `stock += q` |
| ship / deliver | 변화 없음 |

---

## 9. 결제 만료 (R6)

- **`OrderExpirySweeper`**: `@Scheduled(fixedDelayString = "${order.expiry-sweep-interval-ms:500}")`
  1. `now = Times.now(clock)`
  2. `ids = SELECT id FROM orders WHERE status='PENDING_PAYMENT' AND expires_at <= :now ORDER BY expires_at LIMIT 200`. 부분 인덱스 `ix_orders_pending_expires`를 사용한다.
  3. 각 id마다 **개별 트랜잭션**으로 `OrderService.expire(id, now)`를 실행한다.
     ```
     order = SELECT * FROM orders WHERE id=:id AND status='PENDING_PAYMENT' AND expires_at <= :now
             FOR UPDATE SKIP LOCKED                         → 없으면(이미 처리됨/결제·취소 중) skip
     status = EXPIRED; products 오름차순 reserved -= qty; coupon used_count -= 1
     ```
  4. 한 건이 실패해도 로그만 남기고 다음 건으로 넘어간다.
- **2초 반영(R6.2)**: 주기가 500ms이고 처리가 즉시 커밋되므로, `expiresAt` 이후 보통 1초 안에 주문, 상품, 쿠폰 조회에 반영된다.
- **결제와 취소 시점의 만료 판정**: `pay`와 `cancel`은 `now >= expiresAt`이면 상태를 바꾸지 않고 409 `INVALID_STATE`를 반환한다(R5.2). 실제 `EXPIRED` 전이와 복원은 스위퍼만 한다. 그래서 전이 코드 경로가 하나다.
- **경합**:
  - 결제나 취소가 행을 잠그고 있으면 스위퍼는 SKIP LOCKED로 건너뛰고 다음 주기에 재확인한다.
  - 스위퍼가 먼저 잠갔다면 결제나 취소는 블록된다. 스위퍼가 커밋한 뒤 `EXPIRED`를 읽고 409를 반환한다.
- **여러 인스턴스나 테스트 컨텍스트가 같은 DB에서 스위퍼를 동시에 돌려도** SKIP LOCKED와 조건 재확인 덕분에 이중 복원이 일어나지 않는다.
- `OrderApplication`에 `@EnableScheduling`을 붙인다.

---

## 10. 주문 목록, keyset 커서 (R9)

- **정렬**: `ORDER BY created_at DESC, id DESC`. `id`는 `bigint identity`이므로 동률이 깨진다.
- **쿼리**: `OrderQueryRepositoryImpl`이 JPQL을 동적으로 조립한다. PostgreSQL에서 `(:p IS NULL OR ...)` 패턴은 타입 추론 오류를 내기 때문에 쓰지 않는다.
  ```
  select o from Order o
   where 1=1
     [and o.userId = :userId]
     [and o.status = :status]
     [and (o.createdAt < :cAt or (o.createdAt = :cAt and o.id < :cId))]
   order by o.createdAt desc, o.id desc
  ```
  `setMaxResults(size + 1)`로 조회한다. 결과가 `size + 1`개면 다음 페이지가 있다는 뜻이다. 이때 `content`는 앞의 `size`개로 자르고, `nextCursor`는 `content`의 **마지막 원소**로 만든다. 다음 페이지가 없으면 `nextCursor`는 `null`이다.
  `items`는 `@OneToMany` + `hibernate.default_batch_fetch_size=100`으로 한 번의 IN 쿼리로 로드한다. N+1을 피하기 위해서다.
- **커서 형식**: `base64url(무패딩)( UTF-8 "{epochMicros}:{id}" )`
  - `epochMicros = createdAt.getEpochSecond() * 1_000_000 + createdAt.getNano() / 1_000`
  - 디코딩은 `Base64.getUrlDecoder()`로 한다. 패딩이 없어도 허용된다.
  - 다음 경우에는 **400 `VALIDATION_ERROR`**다.
    - base64 디코딩 실패
    - `:`로 나눈 결과가 정확히 2개가 아님
    - 두 값 중 하나라도 `Long.parseLong` 실패
    - `id <= 0`
  - 역변환은 `Instant.ofEpochSecond(floorDiv(m, 1_000_000), floorMod(m, 1_000_000) * 1_000)`이다.
- **R9.5 정확성**:
  - keyset 조건은 위치 기준이므로, 첫 페이지 이후 새 주문이 생겨도 기존 주문들이 밀리거나 중복되지 않는다.
  - 새 주문은 `createdAt`이 더 크다. 같다고 해도 `id`가 더 크므로 커서보다 앞에 정렬되어 이후 페이지에 나타나지 않는다.
  - 이 보장은 `createdAt`이 DB와 앱에서 같은 마이크로초 값을 가질 때만 성립한다(§1.2 절사).
- 필터와 커서는 독립이다. 커서에 필터를 담지 않는다.

---

## 11. 오류 우선순위 C3 적용 요약

| 단계 | 구현 지점 | 비고 |
|---|---|---|
| 1. 400 | MVC 인자 해석과 Bean Validation(컨트롤러 진입 전), 커서·status 파싱(컨트롤러 첫 줄) | 멱등 begin 전에 모든 400을 끝낸다. 목록(E7)은 멱등 대상이 아니다. |
| 2. 422 → 409 멱등 | `OrderFacade` → `IdempotencyStore.begin` | 해시 불일치(422)를 IN_PROGRESS(409)보다 먼저 판정 |
| 3. 404 | 서비스 트랜잭션 첫 단계 | 생성: 상품 → 쿠폰 |
| 4. 409 | 404 이후 | 생성: 재고 → 쿠폰(NOT_APPLICABLE → EXHAUSTED). pay·cancel·ship·deliver: INVALID_STATE |
| 5. PG 402·503 | 409 검사 이후에만 PG 호출 | |

---

## 12. PG 클라이언트 계약

### 12.1 빈 구성 (`PaymentGatewayConfig`)
```java
HttpClient http = HttpClient.newBuilder()
        .connectTimeout(props.timeout())          // 2s
        .version(HttpClient.Version.HTTP_1_1)     // JDK HttpServer 스텁과의 h2c 업그레이드 이슈 회피
        .build();
JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
rf.setReadTimeout(props.timeout());               // 2s: 응답 헤더 수신까지의 요청 타임아웃
RestClient pgRestClient = RestClient.builder()
        .baseUrl(stripTrailingSlash(props.url()))
        .requestFactory(rf)
        .build();
```
- 추가 의존성은 없다. JDK HttpClient와 Spring `RestClient`만 쓴다.
- `PaymentGatewayProperties`는 `@ConfigurationProperties("payment.gateway") record(String url, Duration timeout)`이며 `timeout` 기본은 `PT2S`다.

### 12.2 결제
```
POST {url}/v1/payments
Headers: Content-Type: application/json, Idempotency-Key: {클라이언트가 보낸 Idempotency-Key 그대로}
Body:    {"orderId": <long>, "amount": <long totalPrice>, "cardToken": "<그대로>"}
200 →    {"paymentId": <string|number>, "status": "APPROVED" | "DECLINED"}
```

### 12.3 환불
```
POST {url}/v1/payments/{paymentId}/refund      (본문 없음, paymentId는 URI 템플릿 변수로 인코딩)
200 →    {"paymentId": ..., "status": "REFUNDED"}
```

### 12.4 결과 분류 (`PaymentGatewayClient`)
| 상황 | 처리 |
|---|---|
| 200 + `status=APPROVED` | `PgPaymentResult(paymentId, APPROVED)` |
| 200 + `status=DECLINED` | `PgPaymentResult(paymentId, DECLINED)` |
| 환불 200 + `status=REFUNDED` | 성공 |
| 5xx (`HttpServerErrorException`) | `PaymentGatewayUnavailableException` → 503 |
| 연결 실패나 타임아웃 (`ResourceAccessException`, 원인 `ConnectException`/`HttpTimeoutException`) | 같음 → 503 |
| 4xx, 본문 파싱 실패, 필드 누락, 정의되지 않은 status (`RestClientException` 등) | **같음 → 503**. 계약 밖 응답이므로 상태를 바꾸지 않는 보수적 처리다. |

- 응답은 `JsonNode`로 받는다. `paymentId`는 `asText()`로 읽으므로 숫자와 문자열을 모두 허용한다.
- 재시도는 하지 않는다. 클라이언트가 같은 `Idempotency-Key`로 재시도하면 PG가 최초 결과를 돌려준다.

---

## 13. 설정과 환경 변수 (C4)

`src/main/resources/application.yml` 전체:
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/orders     # SPRING_DATASOURCE_URL 로 덮어씀 (relaxed binding)
    username: orders                                  # SPRING_DATASOURCE_USERNAME
    # password: 기본값 없음 — SPRING_DATASOURCE_PASSWORD 로 주입 (시크릿 하드코딩 금지)
    hikari:
      maximum-pool-size: 20
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_batch_fetch_size: 100
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      accept-float-as-int: false

server:
  port: ${SERVER_PORT:8080}

payment:
  gateway:
    url: ${PAYMENT_GATEWAY_URL:http://localhost:9090}
    timeout: PT2S

order:
  payment-ttl: ${ORDER_PAYMENT_TTL:PT15M}
  expiry-sweep-interval-ms: 500
```

| 환경 변수 | 프로퍼티 | 바인딩 방식 | 기본값 |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | Spring relaxed binding | `jdbc:postgresql://localhost:5432/orders` |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | relaxed binding | `orders` |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | relaxed binding | 없음(주입 필수) |
| `SERVER_PORT` | `server.port` | 플레이스홀더 + relaxed | `8080` |
| `PAYMENT_GATEWAY_URL` | `payment.gateway.url` | 플레이스홀더 | `http://localhost:9090` |
| `ORDER_PAYMENT_TTL` | `order.payment-ttl` | **플레이스홀더 필수**. relaxed binding은 `order.payment.ttl`로 해석되어 맞지 않는다. | `PT15M` |

- `OrderProperties`는 `@ConfigurationProperties("order") record(Duration paymentTtl, long expirySweepIntervalMs)`이다. `PT3S` 같은 ISO-8601 기간이 `Duration`으로 바인딩된다.
- 테스트에서는 Testcontainers `@ServiceConnection`이 datasource를 대체하므로 password 미설정이 문제가 되지 않는다.

---

## 14. 트랜잭션 경계와 잠금 순서 총괄

| 연산 | 트랜잭션 | 잠금 획득 순서 |
|---|---|---|
| 멱등 begin / release | 각각 독립 단기 트랜잭션 (퍼사드, 비즈니스 트랜잭션 밖) | `idempotency_keys` 유니크 인덱스 |
| 주문 생성 | `OrderService.create` 1개 (+ complete) | products(id 오름차순, 조건부 UPDATE) → coupon(FOR NO KEY UPDATE → 조건부 UPDATE) → 새 orders 행 |
| 결제 | `PaymentService.pay` 1개, PG 호출 포함 (+ complete) | orders(FOR UPDATE) → products(오름차순) → coupon |
| 취소·환불 | `OrderService.cancel` 1개, PG 호출 포함 | orders(FOR UPDATE) → products(오름차순) → coupon |
| ship / deliver | 각 1개 | orders(FOR UPDATE) |
| 만료 | 주문 1건당 1개 | orders(FOR UPDATE SKIP LOCKED) → products(오름차순) → coupon |
| 조회·목록 | `readOnly = true` | 없음 |

---

## 15. 테스트 용이성 (test-writer 참고)
- **PG 스텁**: JDK 내장 `com.sun.net.httpserver.HttpServer`를 `0`번 포트에 띄운다. `@DynamicPropertySource`로 `payment.gateway.url=http://localhost:{port}`를 주입한다. 승인, 거절, 5xx, 3초 지연, 서버 정지(연결 실패) 시나리오를 핸들러로 전환하고, 호출 횟수를 `AtomicInteger`로 센다(R10.5).
- **만료 테스트**: 별도 테스트 클래스에 `@TestPropertySource(properties = "order.payment-ttl=PT3S")`를 쓴다. 스위퍼가 실제로 돌기 때문에 `expiresAt + 2s` 안에 상태와 수량이 반영되는지 폴링하면 된다.
- **DB**: `PostgreSQLContainer`(`postgres:16-alpine`) + `@ServiceConnection`. 스키마는 Flyway V1으로 만든다.
- **시각**: `Clock` 빈이다. 필요하면 테스트 설정에서 교체할 수 있다.

---

## 16. 정합 요약 (구현·검증 기준)

### (a) 응답 필드 ↔ 컬럼 대응표

**ProductResponse ↔ `products`**
| 응답 필드 | JSON 타입 | null | 컬럼 | DB 타입 | Java 엔티티 |
|---|---|---|---|---|---|
| `id` | number | N | `products.id` | bigint identity | `Long id` |
| `name` | string | N | `products.name` | varchar(100) NOT NULL | `String name` |
| `price` | number | N | `products.price` | bigint NOT NULL | `long price` |
| `stock` | number | N | `products.stock` | integer NOT NULL | `int stock` (updatable=false) |
| `reserved` | number | N | `products.reserved` | integer NOT NULL DEFAULT 0 | `int reserved` (updatable=false) |
| `available` | number | N | (계산) `stock - reserved` | - | - |
| (미노출) | - | - | `products.created_at` | timestamptz DEFAULT now() | 매핑 생략 가능 |

**CouponResponse ↔ `coupons`**
| 응답 필드 | JSON 타입 | null | 컬럼 | DB 타입 | Java |
|---|---|---|---|---|---|
| (미노출) | - | - | `coupons.id` | bigint identity | `Long id` |
| `code` | string | N | `coupons.code` | varchar(20) NOT NULL UNIQUE | `String code` |
| `type` | string | N | `coupons.type` | varchar(10) CHECK FIXED/RATE | `@Enumerated(STRING) CouponType type` |
| `value` | number | N | `coupons.value` | bigint NOT NULL | `long value` |
| `minOrderAmount` | number | N | `coupons.min_order_amount` | bigint NOT NULL DEFAULT 0 | `long minOrderAmount` |
| `maxDiscountAmount` | number | **Y** | `coupons.max_discount_amount` | bigint NULL | `Long maxDiscountAmount` |
| `totalQuantity` | number | N | `coupons.total_quantity` | bigint NOT NULL | `long totalQuantity` |
| `usedCount` | number | N | `coupons.used_count` | bigint NOT NULL DEFAULT 0 | `long usedCount` (updatable=false) |
| `validFrom` | string ISO-8601 | N | `coupons.valid_from` | timestamptz NOT NULL | `Instant validFrom` |
| `validUntil` | string ISO-8601 | N | `coupons.valid_until` | timestamptz NOT NULL | `Instant validUntil` |

**OrderResponse ↔ `orders` / `order_items`**
| 응답 필드 | JSON 타입 | null | 컬럼 | DB 타입 | Java |
|---|---|---|---|---|---|
| `id` | number | N | `orders.id` | bigint identity | `Long id` |
| `userId` | string | N | `orders.user_id` | varchar(50) NOT NULL | `String userId` |
| `status` | string | N | `orders.status` | varchar(20) CHECK 8개 값 | `@Enumerated(STRING) OrderStatus status` |
| `items[].productId` | number | N | `order_items.product_id` | bigint NOT NULL FK | `Long productId` |
| `items[].quantity` | number | N | `order_items.quantity` | integer NOT NULL | `int quantity` |
| `items[].unitPrice` | number | N | `order_items.unit_price` | bigint NOT NULL | `long unitPrice` |
| (items 순서) | - | - | `order_items.line_no` | integer NOT NULL | `int lineNo`, `@OrderBy("lineNo ASC")` |
| `couponCode` | string | **Y** | `orders.coupon_code` | varchar(20) NULL FK→coupons(code) | `String couponCode` |
| `subtotal` | number | N | `orders.subtotal` | bigint NOT NULL | `long subtotal` |
| `discount` | number | N | `orders.discount` | bigint NOT NULL | `long discount` |
| `totalPrice` | number | N | `orders.total_price` | bigint NOT NULL | `long totalPrice` |
| `createdAt` | string ISO-8601 | N | `orders.created_at` | timestamptz NOT NULL | `Instant createdAt` (마이크로초 절사) |
| `expiresAt` | string ISO-8601 | N | `orders.expires_at` | timestamptz NOT NULL | `Instant expiresAt` |
| `paidAt` | string ISO-8601 | **Y** | `orders.paid_at` | timestamptz NULL | `Instant paidAt` |
| (미노출) | - | - | `orders.payment_id` | varchar(255) NULL | `String paymentId` (환불용) |

**OrderPageResponse**: `content`는 `OrderResponse[]`이고, `nextCursor`는 `string|null`로 마지막 원소의 `(orders.created_at, orders.id)`를 인코딩한 값이다.

**멱등 레코드(미노출)**: `idempotency_keys.response_status`, `response_body`, `response_location`이 재생되는 상태코드, 본문, `Location`과 정확히 같다.

**요청 필수 여부 ↔ nullable 정합**
| 요청 필드 | 필수 | 컬럼 nullable | 정합 |
|---|---|---|---|
| product `name`, `price`, `stock` | Y | NOT NULL | 일치 |
| coupon `minOrderAmount` | N (기본 0) | NOT NULL DEFAULT 0 | 앱에서 null을 0으로 바꿔 저장 |
| coupon `maxDiscountAmount` | N | NULL | 일치 |
| coupon 나머지 | Y | NOT NULL | 일치 |
| order `couponCode` | N | NULL | 일치 |
| order `items[]` | Y | `order_items` NOT NULL | 일치 |
| 헤더 `X-User-Id` | Y | `orders.user_id` NOT NULL | 일치 |

### (b) 제약·비즈니스 규칙 위반 ↔ HTTP 상태·code 매핑표

| # | 위반 | 검출 지점 | HTTP | code |
|---|---|---|---|---|
| 1 | Bean Validation 실패(본문 필드·교차 검증·productId 중복) | MVC | 400 | `VALIDATION_ERROR` |
| 2 | 헤더 누락이나 형식 위반 (`X-User-Id`, `Idempotency-Key`) | MVC | 400 | `VALIDATION_ERROR` |
| 3 | 쿼리 위반(size 범위·타입, 정의되지 않은 status, 잘못된 cursor), 경로 id 타입 | MVC / 컨트롤러 | 400 | `VALIDATION_ERROR` |
| 4 | JSON 파싱 실패, 본문 없음, 숫자 범위 초과, 오프셋 없는 시각 | Jackson | 400 | `VALIDATION_ERROR` |
| 5 | 같은 키 + 다른 요청 해시 | `IdempotencyStore.begin` | 422 | `IDEMPOTENCY_KEY_MISMATCH` |
| 6 | 같은 키의 레코드가 IN_PROGRESS | `IdempotencyStore.begin` | 409 | `IDEMPOTENCY_IN_PROGRESS` |
| 7 | `uq_idempotency_scope_key` 충돌 + COMPLETED + 해시 일치 | `IdempotencyStore.begin` | 저장된 상태(201/200) | (재생) |
| 8 | 없는 상품 (`products.id`) | 서비스 | 404 | `PRODUCT_NOT_FOUND` |
| 9 | 없는 쿠폰 (`coupons.code`) | 서비스 | 404 | `COUPON_NOT_FOUND` |
| 10 | 없는 주문 (`orders.id`) | 서비스 | 404 | `ORDER_NOT_FOUND` |
| 11 | `stock - reserved < qty` (조건부 UPDATE 0행) / `ck_products_reserved` | 생성 ④ | 409 | `INSUFFICIENT_STOCK` |
| 12 | 유효기간 밖, `subtotal < min_order_amount` | 생성 ⑤a·b | 409 | `COUPON_NOT_APPLICABLE` |
| 13 | 같은 사용자의 활성 주문 존재 (EXISTS / `ux_orders_active_user_coupon`) | 생성 ⑤d / ⑥ | 409 | `COUPON_NOT_APPLICABLE` |
| 14 | `used_count >= total_quantity` (조건부 UPDATE 0행) / `ck_coupons_used_count` | 생성 ⑤e | 409 | `COUPON_EXHAUSTED` |
| 15 | `uq_coupons_code` (선조회 또는 INSERT 충돌) | 쿠폰 등록 | 409 | `DUPLICATE_COUPON_CODE` |
| 16 | 상태 전이 불가 / 결제·취소 시점 `now >= expires_at` | pay·cancel·ship·deliver | 409 | `INVALID_STATE` |
| 17 | PG `DECLINED` | 결제 ④ (커밋 후) | 402 | `PAYMENT_DECLINED` |
| 18 | PG 5xx, 연결 실패, 2초 초과, 계약 밖 응답 | `PaymentGatewayClient` | 503 | `PAYMENT_GATEWAY_UNAVAILABLE` |
| 19 | 기타 CHECK(`ck_*`) 위반. 앱 검증으로 사전 차단되므로 발생하면 버그다. | DB | 500 | `INTERNAL_ERROR` (확장) |
| 20 | 미매핑 경로 / 메서드 / 미디어 타입 | MVC | 404 / 405 / 415 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` (확장) |
