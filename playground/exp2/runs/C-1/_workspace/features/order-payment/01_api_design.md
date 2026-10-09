# 01. API 설계 — order-payment

- 원천: `feature.md`(최상위 진실), `00_requirements.json`
- 스택: Java 21 · Spring Boot 3.5.16 · Spring Data JPA/Hibernate · PostgreSQL · Flyway · JUnit 5 + Testcontainers
- DB 설계: [02_db_design.md](./02_db_design.md) (테이블·DDL·락 프로토콜)
- **패키지 루트: `com.example.order`** — 기존 진입점 `src/main/java/com/example/order/OrderApplication.java`(`@SpringBootApplication`)가 이 패키지에 있으므로 컴포넌트 스캔 범위를 맞추기 위해 기존 관례를 따른다. (PM 지시 `com.example.orderservice`와 다름 — 진입점을 옮기지 않는 한 스캔되지 않음.)
  - 하위 패키지: `product`, `coupon`, `order`, `payment`(PG 클라이언트), `idempotency`, `common`(error·time·config)

---

## 0. 공통 규약

### 0.1 타입
| 구분 | JSON | Java DTO | Java 엔티티 | DB |
|---|---|---|---|---|
| 금액 (C1) | number(정수) | `Long` | `long` | `BIGINT` |
| 수량·재고·카운트 | number(정수) | `Integer` | `int` | `INTEGER` |
| ID | number | `Long` | `Long` | `BIGINT` identity |
| 시각 (C2) | string, ISO-8601 + 오프셋 | `OffsetDateTime`(UTC) | `Instant` | `TIMESTAMPTZ` |

- **금액 overflow**: 입력 상한으로 `subtotal ≤ 20 × 1,000 × 10,000,000 = 2×10¹¹`, `subtotal × value ≤ 2×10¹³` → `long` 범위 안이다. 그래도 `Math.multiplyExact`/`Math.addExact`로 계산한다(방어). RATE 할인은 `Math.multiplyExact(subtotal, value) / 100` — 피연산자가 모두 비음수이므로 정수 나눗셈이 곧 floor.
- **시각 정밀도 규칙 (중요)**: 서버가 만드는 모든 시각은 `Instant.now(clock).truncatedTo(ChronoUnit.MICROS)`로 **저장 전에 마이크로초로 절삭**한다(`common.time.TimeProvider.now()` 하나로 통일, `Clock` 빈 주입 — 기본 `Clock.systemUTC()`). 입력 시각(`validFrom`, `validUntil`)도 `toInstant().truncatedTo(MICROS)` 후 저장. 이유: Postgres `timestamptz`는 마이크로초 정밀도라 나노초를 그대로 두면 메모리 값과 DB 값이 달라지고, R9 커서 `(createdAt, id)`가 어긋난다.
- **응답 직렬화**: DTO의 시각은 `instant.atOffset(ZoneOffset.UTC)` → `"2026-10-10T01:02:03.123456Z"` 형태(Jackson 기본 ISO 직렬화, 타임스탬프 숫자 금지). 입력에 `+09:00` 등을 줘도 응답은 같은 순간의 UTC(`Z`)로 정규화된다. **입력 시각에 오프셋이 없으면 파싱 실패 → 400.**
- null 필드는 생략하지 않고 `null`로 직렬화한다(`couponCode`, `paidAt`, `maxDiscountAmount`, `nextCursor`).

### 0.2 에러 포맷 (R11)
모든 오류 응답은 `ProblemDetail`, `Content-Type: application/problem+json`.
```json
{
  "type": "https://api.example.com/problems/insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "상품 10의 가용 재고(3)가 요청 수량(5)보다 적습니다.",
  "code": "INSUFFICIENT_STOCK"
}
```
- `type` = `https://api.example.com/problems/` + code를 소문자·`-`로 바꾼 값. `title`은 code별 고정 문구. `code`는 `pd.setProperty("code", ...)`.
- 구현: `common.error.GlobalExceptionHandler extends ResponseEntityExceptionHandler` (`@RestControllerAdvice`). 도메인 예외는 `ApiException(ErrorCode, detail)` 계열 하나로 두고, `ErrorCode` enum이 `HttpStatus`·title을 보유. 응답은 `ResponseEntity.status(..).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)`.
- **400 `VALIDATION_ERROR`로 매핑해야 하는 프레임워크 예외** (전부): `MethodArgumentNotValidException`(본문 Bean Validation·`@AssertTrue`), `HandlerMethodValidationException`(`@RequestHeader`/`@RequestParam`/`@PathVariable` 제약 — Spring 6.1+ 내장 메서드 검증), `ConstraintViolationException`(혹시 `@Validated` 사용 시), `MissingRequestHeaderException`·`MissingServletRequestParameterException`(→ `ServletRequestBindingException`), `MethodArgumentTypeMismatchException`/`TypeMismatchException`(`size=abc`, `status=FOO`, `/api/orders/abc`), `HttpMessageNotReadableException`(JSON 파싱 실패, 본문 누락, 알 수 없는 enum `type=FOO`, 오프셋 없는 시각, long 범위 초과), `InvalidCursorException`(자체). → `handleExceptionInternal` 오버라이드에서 status 400이면 code=`VALIDATION_ERROR`, 그 외 Spring 기본(404 미매핑 경로, 405, 415 등)은 ProblemDetail 그대로(코드 부여는 선택).
- 400 응답에는 확장 필드 `errors: [{field, message}]`를 넣어도 된다(선택).
- 500: 내부 메시지 미노출, code `INTERNAL_ERROR`(표 외, 정상 경로에서는 발생하지 않아야 함).

### 0.3 Jackson 설정 (400 판정 보강)
`spring.jackson.deserialization.accept-float-as-int: false`(`quantity: 1.5` → 400), `spring.jackson.deserialization.fail-on-numbers-for-enums: true`(`type: 0` → 400). 알 수 없는 필드는 무시(Boot 기본).

### 0.4 오류 우선순위 (C3) — 전 엔드포인트 공통 골격
`400(프레임워크 검증, 컨트롤러 진입 전)` → `멱등(422 → 409 IDEMPOTENCY_IN_PROGRESS → 재생)` → `404` → `409(재고 → 쿠폰 → 상태)` → `PG(402·503)`.
엔드포인트별 구체 순서는 각 절의 **검증 순서**를 따른다. 한 단계에서 실패하면 즉시 그 오류를 반환한다(이후 단계 미평가).

### 0.5 Location
상대 URI: `/api/products/{id}`, `/api/coupons/{code}`, `/api/orders/{id}`. 멱등 재생 시에도 저장된 값을 그대로 돌려준다.

---

## 1. 상품 (R1)

### POST /api/products
요청
```json
{ "name": "키보드", "price": 15000, "stock": 10 }
```
| 필드 | 규칙 (위반 → 400) |
|---|---|
| name | `@NotBlank @Size(max=100)` |
| price | `@NotNull @Min(1) @Max(10_000_000)` Long |
| stock | `@NotNull @Min(0) @Max(1_000_000)` Integer |

응답 `201`, `Location: /api/products/1`
```json
{ "id": 1, "name": "키보드", "price": 15000, "stock": 10, "reserved": 0, "available": 10 }
```

### GET /api/products/{id}
`200` 위와 같은 형태(`available = stock − reserved`, 컬럼 아님). 없으면 `404 PRODUCT_NOT_FOUND`. `id` 숫자 아님 → 400.

---

## 2. 쿠폰 (R2)

### POST /api/coupons
요청
```json
{
  "code": "WELCOME10", "type": "RATE", "value": 10,
  "minOrderAmount": 10000, "maxDiscountAmount": 5000, "totalQuantity": 100,
  "validFrom": "2026-01-01T00:00:00+09:00", "validUntil": "2027-01-01T00:00:00+09:00"
}
```
| 필드 | 규칙 (위반 → 400) | 생략 시 |
|---|---|---|
| code | `@NotNull @Pattern("^[A-Z0-9]{4,20}$")` | 400 |
| type | `@NotNull` enum `CouponType{FIXED, RATE}` (미정의 값 → 파싱 실패 400) | 400 |
| value | `@NotNull @Min(1)` Long; `type=RATE`이면 `≤ 100` (`@AssertTrue isValueInRange()`) | 400 |
| minOrderAmount | `@Min(0)` Long, nullable | **0** |
| maxDiscountAmount | `@Min(1)` Long, nullable | **null(제한 없음)** |
| totalQuantity | `@NotNull @Min(1)` Integer | 400 |
| validFrom, validUntil | `@NotNull` OffsetDateTime(오프셋 필수); `validFrom < validUntil` (`@AssertTrue isPeriodValid()`) | 400 |

검증 순서: 400(위 전체) → `409 DUPLICATE_COUPON_CODE`(`existsByCode` 선검사 + `uk_coupons_code` 위반 `DataIntegrityViolationException`도 같은 409로 변환 — `saveAndFlush`로 즉시 감지).

응답 `201`, `Location: /api/coupons/WELCOME10`
```json
{
  "code": "WELCOME10", "type": "RATE", "value": 10,
  "minOrderAmount": 10000, "maxDiscountAmount": 5000,
  "totalQuantity": 100, "usedCount": 0,
  "validFrom": "2025-12-31T15:00:00Z", "validUntil": "2026-12-31T15:00:00Z"
}
```

### GET /api/coupons/{code}
`200` 위 형태. 없으면 `404 COUPON_NOT_FOUND`(형식이 틀린 code도 단순히 404).

### 할인 계산 (R2.4) — `CouponPolicy.discount(coupon, subtotal)`
```
raw   = FIXED ? value : Math.multiplyExact(subtotal, value) / 100
d     = maxDiscountAmount == null ? raw : min(raw, maxDiscountAmount)
d     = min(d, subtotal)
total = subtotal - d
```

### 쿠폰 적용 판정 (R2.5) — 요청 시각 `now` 기준, 이 순서로
1. `validFrom ≤ now < validUntil` 아니면 → `409 COUPON_NOT_APPLICABLE`
2. `subtotal < minOrderAmount` → `409 COUPON_NOT_APPLICABLE`
3. 같은 `userId`의 활성 주문(`PENDING_PAYMENT`·`PAID`·`SHIPPED`·`DELIVERED`)이 이 쿠폰을 사용 중 → `409 COUPON_NOT_APPLICABLE`
4. `usedCount ≥ totalQuantity` → `409 COUPON_EXHAUSTED`

`now`는 서비스 진입 시 한 번 잡은 `TimeProvider.now()`(= 주문 `createdAt`과 동일 값).

---

## 3. 주문 생성 (R3, R4)

### POST /api/orders
헤더
| 헤더 | 규칙 (위반·누락 → 400) |
|---|---|
| `X-User-Id` | 필수, `@NotBlank @Size(max=50)` |
| `Idempotency-Key` | 필수, `@Size(min=1, max=64)` |

요청
```json
{ "items": [ { "productId": 1, "quantity": 2 }, { "productId": 2, "quantity": 1 } ], "couponCode": "WELCOME10" }
```
| 필드 | 규칙 (위반 → 400) |
|---|---|
| items | `@NotNull @Size(min=1, max=20)` `List<@NotNull @Valid Item>`; productId 중복 불가(`@AssertTrue hasNoDuplicateProduct()`, items null이면 true 반환) |
| items[].productId | `@NotNull` Long (존재 여부는 404 단계) |
| items[].quantity | `@NotNull @Min(1) @Max(1000)` Integer |
| couponCode | nullable String. null이면 쿠폰 없음. 그 외 값은 그대로 조회(없으면 404) |

**검증 순서**
1. 400 — 헤더·본문 (컨트롤러 진입 전).
2. 멱등 클레임 `scope=CREATE_ORDER` (§3.1) → 422 / 409 IDEMPOTENCY_IN_PROGRESS / 저장 응답 재생(201).
3. 트랜잭션 시작, `now = TimeProvider.now()`.
4. 상품을 **productId 오름차순으로 하나씩** `findByIdForUpdate`(비관적 락). 없으면 `404 PRODUCT_NOT_FOUND`.
5. `couponCode != null`이면 `findByCodeForUpdate`. 없으면 `404 COUPON_NOT_FOUND`.
6. 요청 항목 순서대로 `stock − reserved < quantity`인 항목이 있으면 `409 INSUFFICIENT_STOCK`.
7. `subtotal = Σ addExact(multiplyExact(price, quantity))`(가격은 락으로 읽은 현재 가격).
8. 쿠폰 판정(§2, 1→4) → `409 COUPON_NOT_APPLICABLE` / `409 COUPON_EXHAUSTED`.
9. 반영: 각 상품 `reserved += quantity`, 쿠폰 `usedCount += 1`, 주문 INSERT(`status=PENDING_PAYMENT`, `createdAt=now`, `expiresAt=now + order.payment-ttl`, items의 `unitPrice=price`, `lineNo`=요청 순서 0..n-1), `flush`.
10. 응답 DTO 생성 → 멱등 레코드를 `COMPLETED(201, body, location)`로 갱신(같은 트랜잭션) → 커밋.
11. 2~10 중 어디서든 예외 → 멱등 레코드 삭제(별도 트랜잭션) 후 예외 전파(R4.4).

→ 예: 상품 하나가 없고 쿠폰도 부적용 → 404. 재고 부족과 쿠폰 소진이 함께 → `INSUFFICIENT_STOCK`. 4~9는 한 트랜잭션이라 예약·쿠폰 사용은 전부 또는 전무(R3.4).

응답 `201`, `Location: /api/orders/1`, 본문 = §4 OrderResponse (`status=PENDING_PAYMENT`, `paidAt=null`).

### 3.1 멱등성 처리 (R4) — `idempotency.IdempotencyService` + 엔드포인트별 퍼사드

**지문(fingerprint)** = `SHA-256 hex( userId + "\n" + "POST " + path + "\n" + canonicalBody )`
- `userId`: `X-User-Id` 값(결제처럼 헤더가 선택이면 없을 때 `""`).
- `path`: 해석된 경로 문자열. CREATE_ORDER → `/api/orders`, PAY_ORDER → `"/api/orders/" + id + "/pay"`(경로 변수 Long을 다시 문자열화).
- `canonicalBody`: **검증을 통과한 요청 DTO**를 전용 `ObjectMapper`(기본 설정, 들여쓰기 없음)로 재직렬화한 JSON. 공백·필드 순서·알 수 없는 필드 차이는 무시되고, `items` 순서·값 차이는 다른 요청으로 본다. `cardToken`은 해시에만 들어가고 평문 저장하지 않는다.

**클레임 (별도 트랜잭션 `REQUIRES_NEW` 또는 `TransactionTemplate`, 즉시 커밋)**
```sql
INSERT INTO idempotency_records (scope, idem_key, fingerprint, status, created_at)
VALUES (:scope, :key, :fp, 'IN_PROGRESS', :now)
ON CONFLICT (scope, idem_key) DO NOTHING
```
- 영향 행 1 → 이 요청이 소유자. 레코드 id를 들고 처리 진행.
- 영향 행 0 → `SELECT ... WHERE scope=:scope AND idem_key=:key`:
  1. 행이 없음(소유자가 실패해 방금 삭제) → INSERT부터 재시도(최대 3회, 그래도 실패면 409 IDEMPOTENCY_IN_PROGRESS).
  2. `fingerprint ≠ :fp` → `422 IDEMPOTENCY_KEY_MISMATCH`.
  3. `status = IN_PROGRESS` → `409 IDEMPOTENCY_IN_PROGRESS`.
  4. `status = COMPLETED` → 저장된 `response_status`·`response_body`(Content-Type `application/json`)·`response_location`(있으면 `Location`)로 **처리 없이 재생**.

**완료/해제**
- 성공(2xx): 비즈니스 트랜잭션 안에서 `status=COMPLETED, response_status, response_body(JSON 문자열), response_location, completed_at` 갱신 → 비즈니스 반영과 원자적.
- 오류(4xx/5xx, 402 포함): 퍼사드가 `catch (RuntimeException e)`에서 `DELETE FROM idempotency_records WHERE id=:id AND status='IN_PROGRESS'`(별도 트랜잭션) 후 재던짐.
- 재생 본문은 최초 응답 시점의 스냅숏이다(이후 주문 상태가 바뀌어도 그대로).
- 키 공간은 `(scope, idem_key)` 유니크라 CREATE_ORDER와 PAY_ORDER가 독립(R4.1). 같은 scope 안에서는 사용자와 무관하게 키가 하나 — 다른 `X-User-Id`로 같은 키를 쓰면 422.
- 프로세스 중단으로 남은 IN_PROGRESS 레코드 정리는 범위 외(멱등 키 만료 제외).

---

## 4. 주문 조회 (R3.5)

### GET /api/orders/{id}
`200` OrderResponse. 없으면 `404 ORDER_NOT_FOUND`. 헤더 불필요.
```json
{
  "id": 1,
  "userId": "user-1",
  "status": "PENDING_PAYMENT",
  "items": [
    { "productId": 1, "quantity": 2, "unitPrice": 15000 },
    { "productId": 2, "quantity": 1, "unitPrice": 30000 }
  ],
  "couponCode": "WELCOME10",
  "subtotal": 60000,
  "discount": 5000,
  "totalPrice": 55000,
  "createdAt": "2026-10-10T01:02:03.123456Z",
  "expiresAt": "2026-10-10T01:17:03.123456Z",
  "paidAt": null
}
```
- `status` ∈ `PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, CANCELLED, SHIPPED, DELIVERED, REFUNDED`.
- `items`는 요청 순서(`line_no` 오름차순).

---

## 5. 주문 목록 (R9)

### GET /api/orders?userId=&status=&size=&cursor=
| 파라미터 | 규칙 |
|---|---|
| userId | 선택, 정확히 일치 필터 |
| status | 선택, `OrderStatus` enum. 미정의 값 → 400(타입 변환 실패) |
| size | 선택, 기본 20, `@Min(1) @Max(100)` Integer. `abc`·0·101 → 400 |
| cursor | 선택. 해석 불가 → 400 `VALIDATION_ERROR`(`InvalidCursorException`) |

응답 `200`
```json
{ "content": [ /* OrderResponse ... */ ], "nextCursor": "MjAyNi0xMC0xMFQwMTowMjowMy4xMjM0NTZafDQy" }
```
- 정렬 `created_at DESC, id DESC`. 조건: `userId`/`status` 필터 AND, 커서가 있으면 `(created_at < :c) OR (created_at = :c AND id < :id)`.
- `size + 1`건 조회 → 초과분이 있으면 `content`는 앞 `size`건, `nextCursor` = 마지막 원소로 인코딩. 아니면 `nextCursor = null`.
- **커서 포맷**: `Base64.getUrlEncoder().withoutPadding()` of UTF-8 `"{createdAt Instant.toString()}|{id}"` (예: `2026-10-10T01:02:03.123456Z|42`). 디코딩: base64url 실패, `|` 분리 결과 2개 아님, `Instant.parse` 실패, `Long.parseLong` 실패 → 400.
- 구현: `JpaSpecificationExecutor` + `findBy(spec, q -> q.sortBy(sort).limit(size + 1).all())` 또는 Criteria/동적 JPQL. (`:p is null or ...` 형태의 JPQL은 null 타입 추론 문제가 있으니 피한다.) items는 `hibernate.default_batch_fetch_size`로 일괄 로딩.
- **R9.5 근거**: 키 `(created_at, id)`는 불변이고 `id`가 유일해 전순서다. 다음 페이지는 오프셋이 아니라 "직전 마지막 키보다 엄격히 작은 키"로 조회하므로 앞쪽에 새 행이 끼어도 이미 본 행/아직 안 본 행의 경계가 움직이지 않는다 → 첫 페이지 시점의 주문은 정확히 한 번씩 나온다. 커서가 DB 값과 정확히 같으려면 `created_at`이 저장 전에 마이크로초로 절삭되어 있어야 한다(§0.1).

---

## 6. 결제 (R5)

### POST /api/orders/{id}/pay
헤더 `Idempotency-Key` 필수 `@Size(min=1, max=64)`. `X-User-Id`는 선택(검증하지 않음, 지문에만 포함).
요청 `{ "cardToken": "tok_visa_123" }` — `@NotBlank`.

**검증·처리 순서**
1. 400 — `id` 타입, 헤더, `cardToken`.
2. 멱등 클레임 `scope=PAY_ORDER`, path=`/api/orders/{id}/pay` → 422 / 409 IDEMPOTENCY_IN_PROGRESS / 재생(200). 같은 키·다른 주문 경로 → 지문 불일치 → 422.
3. 트랜잭션 시작(`@Transactional(noRollbackFor = {InvalidStateException.class, PaymentDeclinedException.class})`). `order = findByIdForUpdate(id)`(주문 행 `FOR UPDATE`) → 없으면 `404 ORDER_NOT_FOUND`.
4. `now = TimeProvider.now()`.
   - `status == PENDING_PAYMENT && now ≥ expiresAt` → 그 자리에서 만료 처리(§9 동일 로직, EXPIRED + 예약·쿠폰 복원) 후 `409 INVALID_STATE`(커밋됨).
   - `status != PENDING_PAYMENT` → `409 INVALID_STATE`.
5. `totalPrice == 0` → PG 호출 없이 6(승인)으로. `pg_payment_id = null`.
   아니면 PG `POST /v1/payments` (헤더 `Idempotency-Key` = 클라이언트 키 그대로, 본문 `{orderId: id, amount: totalPrice, cardToken}`) — **주문 행 락을 쥔 채 호출**.
   - 5xx, 4xx, 연결 실패, 2초 초과, 본문 해석 실패, `status`가 APPROVED/DECLINED 외 → `PaymentGatewayUnavailableException` → 롤백 → `503 PAYMENT_GATEWAY_UNAVAILABLE`. 주문·재고·쿠폰 불변.
6. `APPROVED`: 상품을 id 오름차순으로 락, 각 `stock −= q`, `reserved −= q`; 주문 `status=PAID`, `paidAt = TimeProvider.now()`(PG 응답 후 시각), `pg_payment_id = paymentId`; 멱등 레코드 COMPLETED(200, body) → 커밋 → `200` OrderResponse.
7. `DECLINED`: 상품 id 오름차순 락 `reserved −= q`, 쿠폰 있으면 락 후 `usedCount −= 1`; 주문 `status=PAYMENT_FAILED`, `pg_payment_id = paymentId` → `PaymentDeclinedException` 던짐(noRollbackFor로 커밋) → 퍼사드가 멱등 레코드 삭제 → `402 PAYMENT_DECLINED`.

**R10.5 (같은 주문·다른 키 동시 결제)**: 3단계 주문 행 `FOR UPDATE`가 직렬화 지점이다. 승자가 PG 호출~커밋까지 락을 쥐므로 패자는 대기 후 `PAID`를 보고 **`409 INVALID_STATE`**를 받는다 → PG 결제 호출 최대 1회, 성공 응답 1건. 승자가 503으로 롤백했다면 패자는 여전히 `PENDING_PAYMENT`를 보고 정상 진행한다(순차적 재시도로 간주).
트레이드오프: PG 호출 동안 DB 커넥션·주문 행 락을 최대 ~2초 점유한다 → Hikari 풀 20으로 확장(§11). 상품·쿠폰 락은 PG 응답 이후에만 잡으므로 주문 생성은 막지 않는다.

응답 `200` OrderResponse(`status=PAID`, `paidAt` 채움).

### PG 클라이언트 — `payment.PaymentGatewayClient`
- `RestClient` + `SimpleClientHttpRequestFactory`: `connectTimeout = 2s`, `readTimeout = 2s` (`payment.gateway.connect-timeout`/`read-timeout`). baseUrl = `payment.gateway.url`.
- 메서드: `PgPayResult pay(String idempotencyKey, long orderId, long amount, String cardToken)`, `PgRefundResult refund(String paymentId)`.
- 응답은 `JsonNode`로 받아 `paymentId`는 `asText()`(PG가 숫자로 줘도 수용), `status`는 문자열 비교.
- `RestClientException`(`ResourceAccessException` 포함)·`HttpStatusCodeException` 전부 → `PaymentGatewayUnavailableException`.

---

## 7. 취소·환불 (R7)

### POST /api/orders/{id}/cancel
본문·헤더 없음. 멱등 처리 없음.

**순서** (`@Transactional(noRollbackFor = InvalidStateException.class)`)
1. 400 — `id` 타입.
2. `findByIdForUpdate(id)` → 없으면 `404 ORDER_NOT_FOUND`.
3. `now`; `PENDING_PAYMENT && now ≥ expiresAt` → 만료 처리 후 `409 INVALID_STATE`.
4. `PENDING_PAYMENT` → 상품 락(id 오름차순) `reserved −= q`, 쿠폰 복원, `status=CANCELLED` → `200`.
5. `PAID`:
   - `pg_payment_id == null`(totalPrice=0으로 PG 미경유 결제) → PG 호출 없이 바로 환불 반영.
   - 아니면 PG `POST /v1/payments/{pgPaymentId}/refund`. 5xx·4xx·연결 실패·2초 초과·`status != "REFUNDED"` → 롤백 → `503 PAYMENT_GATEWAY_UNAVAILABLE`(불변).
   - 환불 반영: 상품 락 `stock += q`(reserved는 결제 때 이미 차감됨), 쿠폰 복원, `status=REFUNDED` → `200`.
6. 그 밖(`SHIPPED`, `DELIVERED`, `CANCELLED`, `EXPIRED`, `PAYMENT_FAILED`, `REFUNDED`) → `409 INVALID_STATE`.

응답 `200` OrderResponse.

---

## 8. 배송 (R8)

### POST /api/orders/{id}/ship · POST /api/orders/{id}/deliver
순서: 400(id 타입) → `findByIdForUpdate` 없으면 404 → 상태 검사.
- ship: `PAID → SHIPPED`, 그 외 `409 INVALID_STATE`.
- deliver: `SHIPPED → DELIVERED`, 그 외 `409 INVALID_STATE`.
응답 `200` OrderResponse. 재고·쿠폰 변화 없음(쿠폰은 계속 사용 중으로 집계).

---

## 9. 결제 만료 (R6) — 백그라운드

- `order.OrderExpiryScheduler` `@Scheduled(fixedDelayString = "${order.expiry-sweep-delay-ms:250}")`, `@EnableScheduling`.
- 매 실행: `now`; 후보 `SELECT id FROM orders WHERE status='PENDING_PAYMENT' AND expires_at <= :now ORDER BY expires_at, id LIMIT 200`(락 없음).
- 후보마다 **주문 1건 = 트랜잭션 1개**(다른 빈의 `@Transactional(REQUIRES_NEW)` 메서드 — self-invocation 금지):
  `SELECT * FROM orders WHERE id=:id AND status='PENDING_PAYMENT' AND expires_at <= :now FOR UPDATE SKIP LOCKED` → 없으면 skip. 있으면 상품 락(id 오름차순) `reserved −= q`, 쿠폰 복원, `status=EXPIRED`.
- **결제와 경합**: 결제 요청이 주문 행을 잡고 PG 호출 중이면 스윕은 `SKIP LOCKED`로 건너뛴다. 결제가 락 획득 시점에 만료 전이었다면 결제가 이긴다(승인 → PAID, 503 → 다음 스윕에서 EXPIRED). 결제가 락 획득 시점에 `now ≥ expiresAt`이면 결제가 직접 만료 처리하고 409. 즉 **"주문 행 락을 잡은 시점의 now"로 판정**한다.
- 2초 보장: 스윕 간격 250ms + 처리 시간. 조회(GET)는 별도 지연 만료를 하지 않는다.
- TTL: `order.payment-ttl`(java.time.Duration, ISO-8601 `PT3S` 등), 기본 `PT15M`.

---

## 10. 상태 전이 요약
| 현재 | pay | cancel | ship | deliver | 만료 |
|---|---|---|---|---|---|
| PENDING_PAYMENT | PAID / PAYMENT_FAILED(402) / 불변(503) | CANCELLED | 409 | 409 | EXPIRED |
| PAID | 409 | REFUNDED / 불변(503) | SHIPPED | 409 | - |
| SHIPPED | 409 | 409 | 409 | DELIVERED | - |
| 그 밖 | 409 | 409 | 409 | 409 | - |

복원 규칙: `CANCELLED`·`EXPIRED`·`PAYMENT_FAILED`는 `reserved −= q` + 쿠폰 `usedCount −= 1`, `REFUNDED`는 `stock += q` + 쿠폰 `usedCount −= 1`.

---

## 11. 설정 (C4) — `src/main/resources/application.yml`
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/orders}
    username: ${SPRING_DATASOURCE_USERNAME:orders}
    password: ${SPRING_DATASOURCE_PASSWORD:}
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
    enabled: true
  jackson:
    deserialization:
      accept-float-as-int: false
      fail-on-numbers-for-enums: true
server:
  port: ${SERVER_PORT:8080}
payment:
  gateway:
    url: ${PAYMENT_GATEWAY_URL:http://localhost:9090}
    connect-timeout: 2s
    read-timeout: 2s
order:
  payment-ttl: ${ORDER_PAYMENT_TTL:PT15M}
  expiry-sweep-delay-ms: 250
```
- `@ConfigurationProperties("payment.gateway") record PaymentGatewayProperties(URI url, Duration connectTimeout, Duration readTimeout)`
- `@ConfigurationProperties("order") record OrderProperties(Duration paymentTtl, long expirySweepDelayMs)`
- `OrderApplication`에 `@ConfigurationPropertiesScan`, `@EnableScheduling` 추가. `Clock` 빈(`Clock.systemUTC()`).
- 테스트는 `@ServiceConnection` PostgreSQLContainer + `@DynamicPropertySource`로 `payment.gateway.url`, `order.payment-ttl` 덮어쓰기.

---

## 12. 정합 요약

구현·검증 단계의 기준표다. `02_db_design.md`는 이 표를 참조만 한다.

### (a) 응답/요청 필드 ↔ 테이블 컬럼

**ProductResponse ↔ `products`**
| JSON | Java | 컬럼 | 타입 / null | 비고 |
|---|---|---|---|---|
| id | Long | id | BIGINT identity / NN | |
| name | String | name | VARCHAR(100) / NN | 요청 필수 |
| price | Long | price | BIGINT / NN | 요청 필수 |
| stock | Integer | stock | INTEGER / NN | 요청 필수 |
| reserved | Integer | reserved | INTEGER / NN, DEFAULT 0 | 요청 없음 |
| available | Integer | (없음) | — | `stock − reserved` 계산값 |

**CouponResponse ↔ `coupons`**
| JSON | Java | 컬럼 | 타입 / null | 비고 |
|---|---|---|---|---|
| code | String | code | VARCHAR(20) / NN, UNIQUE | 요청 필수 |
| type | CouponType | discount_type | VARCHAR(10) / NN | 요청 필수 |
| value | Long | discount_value | BIGINT / NN | 요청 필수 |
| minOrderAmount | Long | min_order_amount | BIGINT / NN, DEFAULT 0 | 요청 선택 → 앱이 0 채움 |
| maxDiscountAmount | Long(null 가능) | max_discount_amount | BIGINT / **NULL** | 요청 선택 → null |
| totalQuantity | Integer | total_quantity | INTEGER / NN | 요청 필수 |
| usedCount | Integer | used_count | INTEGER / NN, DEFAULT 0 | 요청 없음 |
| validFrom | OffsetDateTime(UTC) | valid_from | TIMESTAMPTZ / NN | 요청 필수 |
| validUntil | OffsetDateTime(UTC) | valid_until | TIMESTAMPTZ / NN | 요청 필수 |
| — | — | id | BIGINT identity | **비노출** (내부 PK) |

**OrderResponse ↔ `orders` / `order_items`**
| JSON | Java | 컬럼 | 타입 / null | 비고 |
|---|---|---|---|---|
| id | Long | orders.id | BIGINT identity / NN | |
| userId | String | orders.user_id | VARCHAR(50) / NN | `X-User-Id` |
| status | OrderStatus | orders.status | VARCHAR(20) / NN | |
| items[].productId | Long | order_items.product_id | BIGINT / NN, FK | |
| items[].quantity | Integer | order_items.quantity | INTEGER / NN | |
| items[].unitPrice | Long | order_items.unit_price | BIGINT / NN | 주문 시점 가격 |
| couponCode | String(null 가능) | orders.coupon_code | VARCHAR(20) / **NULL**, FK | 요청 선택 |
| subtotal | Long | orders.subtotal | BIGINT / NN | |
| discount | Long | orders.discount | BIGINT / NN | 쿠폰 없으면 0 |
| totalPrice | Long | orders.total_price | BIGINT / NN | |
| createdAt | OffsetDateTime(UTC) | orders.created_at | TIMESTAMPTZ / NN | µs 절삭 |
| expiresAt | OffsetDateTime(UTC) | orders.expires_at | TIMESTAMPTZ / NN | createdAt + TTL |
| paidAt | OffsetDateTime(null 가능) | orders.paid_at | TIMESTAMPTZ / **NULL** | |
| — | — | orders.pg_payment_id | VARCHAR(100) / NULL | **비노출** (환불용) |
| — | — | order_items.id, order_id, line_no | | **비노출** (line_no = items 순서) |

**OrderPageResponse**: `content` = OrderResponse[], `nextCursor` = base64url(`created_at|id`) of 마지막 원소 또는 null — 컬럼 아님.

**멱등 (`idempotency_records`, 응답 비노출)**: scope ← 엔드포인트(CREATE_ORDER/PAY_ORDER), idem_key ← `Idempotency-Key`, fingerprint ← §3.1, response_status/response_body/response_location ← 재생용 최초 2xx 응답.

### (b) 제약·비즈니스 규칙 위반 ↔ HTTP 상태·code

| 단계(C3) | 위반 | 근거(제약/규칙) | HTTP | code |
|---|---|---|---|---|
| 1 | 본문 Bean Validation·교차 검증(RATE value>100, validFrom≥validUntil, productId 중복) | DTO 제약 (DB CHECK는 최후 방어) | 400 | VALIDATION_ERROR |
| 1 | 헤더 누락·위반, 쿼리 타입 오류·범위(size), 미정의 status/type, JSON 파싱 실패, 경로 id 타입, 잘못된 cursor | 프레임워크 예외 (§0.2) | 400 | VALIDATION_ERROR |
| 2 | 같은 scope·키, 지문 불일치 | `idempotency_records.fingerprint` | 422 | IDEMPOTENCY_KEY_MISMATCH |
| 2 | 같은 scope·키, 처리 중 | `idempotency_records.status = IN_PROGRESS` / `uk_idem_scope_key` | 409 | IDEMPOTENCY_IN_PROGRESS |
| 3 | 상품 없음 (주문 생성, GET) | `products.id`, FK `order_items.product_id` | 404 | PRODUCT_NOT_FOUND |
| 3 | 쿠폰 없음 (주문 생성, GET) | `coupons.code`, FK `orders.coupon_code` | 404 | COUPON_NOT_FOUND |
| 3 | 주문 없음 (GET, pay, cancel, ship, deliver) | `orders.id` | 404 | ORDER_NOT_FOUND |
| 4 | 쿠폰 code 중복 | `uk_coupons_code` | 409 | DUPLICATE_COUPON_CODE |
| 4 | 가용 재고 부족 (재고가 쿠폰보다 먼저) | `ck_products_reserved` (`reserved ≤ stock`) | 409 | INSUFFICIENT_STOCK |
| 4 | 기간 밖·최소금액 미달·같은 사용자 사용 중 | 규칙 R2.5 / `ux_orders_user_coupon_active` | 409 | COUPON_NOT_APPLICABLE |
| 4 | 소진 | `ck_coupons_used_count` (`used_count ≤ total_quantity`) | 409 | COUPON_EXHAUSTED |
| 4 | 상태 전이 불가·결제 시 만료 | `orders.status` 전이 규칙 (§10) | 409 | INVALID_STATE |
| 5 | PG 거절 | PG `status=DECLINED` | 402 | PAYMENT_DECLINED |
| 5 | PG 5xx·연결 실패·2초 초과·비정상 응답 (결제·환불) | PG 계약 | 503 | PAYMENT_GATEWAY_UNAVAILABLE |

- 코드 경로가 정상이면 `ck_*`·`ux_orders_user_coupon_active` 위반은 발생하지 않는다(락으로 선검사). 만약 `ux_orders_user_coupon_active` 위반이 flush에서 나면 409 COUPON_NOT_APPLICABLE로 변환, 그 밖의 CHECK 위반은 버그로 보고 500.
