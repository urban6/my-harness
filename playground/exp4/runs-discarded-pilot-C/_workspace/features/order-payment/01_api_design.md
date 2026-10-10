# order-payment — API 설계 (01_api_design.md)

스택: Java 21 / Spring Boot 3.5.16 / Spring Data JPA(Hibernate) / PostgreSQL / Flyway / JUnit5 + Testcontainers. 테스트 명령 `./gradlew test`.
DB·락·트랜잭션 메커니즘은 `02_db_design.md`가 소유한다. 이 문서는 외부에 보이는 계약(엔드포인트·검증·오류 우선순위·흐름 단계)을 소유한다.
`[ASSUMPTION.]` 표시는 요구사항이 침묵하거나 모호해 설계자가 정한 지점이며 12절에 모아 둔다.

---

## 0. 공통 규약 적용

| 규약 | 설계 결정 |
|---|---|
| C1 금액 | 모든 금액·수량은 Java `long` / DB `BIGINT` / JSON number 그대로. `subtotal` 최대 = 20 × 10,000,000 × 1,000 = 2×10^11 이라 `long` 안전. 문자열로 직렬화하지 않는다. |
| C2 시각 | DB `TIMESTAMPTZ`, 엔티티 `java.time.Instant`. 응답은 항상 UTC 오프셋(`Z`)이 붙은 ISO-8601 (예 `2026-10-10T03:00:00.123456Z`). 요청 시각은 오프셋이 반드시 있어야 한다(없으면 400). 정밀도 처리는 0.2절. |
| C3 | 오류 우선순위 400 → 멱등 키(422·409) → 404 → 409(재고 → 쿠폰) → PG(402·503). 단계 번호는 5·6·7절. |
| C4 | 설정·환경 변수 매핑은 10절. |

### 0.1 Jackson 설정 (필수, `application.yml` + 커스터마이저)

```
spring.jackson.serialization.write-dates-as-timestamps: false      # ISO-8601 문자열
spring.jackson.default-property-inclusion: always                  # null 필드도 출력 (couponCode, paidAt, maxDiscountAmount, nextCursor)
spring.jackson.time-zone: UTC
spring.jackson.deserialization.accept-float-as-int: false          # price: 10.5 / 100.0 -> 400
spring.jackson.deserialization.fail-on-numbers-for-enums: true     # type: 0 -> 400
spring.jackson.deserialization.fail-on-null-for-primitives: true
spring.jackson.mapper.allow-coercion-of-scalars: false             # price: "100" -> 400
```
추가 커스터마이저(`Jackson2ObjectMapperBuilderCustomizer`): `String` 대상 역직렬화는 JSON 문자열 토큰만 허용(`name: 123`, `cardToken: 1` -> 400). 위 속성이 일부 케이스를 거르지 못하면 이 strict 역직렬화로 막는다 — **테스트로 실제 400임을 확인**할 것(구현·테스트 단계 책임).
- 요청 DTO 숫자는 전부 박싱 `Long`(null 허용 후 `@NotNull`로 400). `int` 범위 초과(`3000000000`), 소수, 문자열, long 범위 초과(`1e30`) 모두 `HttpMessageNotReadableException` -> 400 `VALIDATION_ERROR`.
- 요청 DTO의 시각(`validFrom`, `validUntil`)은 `String`으로 받아 `OffsetDateTime.parse`(ISO_OFFSET_DATE_TIME)로 직접 파싱한다. 오프셋 없음·숫자 타임스탬프·형식 오류 -> 400. 연도는 0001~9999만 허용(PostgreSQL 범위 방어). 파싱 후 `Instant`로 변환하고 **마이크로초로 절삭**한 값으로 `validFrom < validUntil`을 판정한다.
- 알 수 없는 JSON 필드는 무시(Boot 기본, `fail-on-unknown-properties=false`). `[ASSUMPTION.]` 요구사항에 거부 규정이 없다.

### 0.2 시각 정밀도 (PostgreSQL 마이크로초 vs Java 나노초)

- PostgreSQL `TIMESTAMPTZ`는 마이크로초. JDK 21 `Instant.now()`/`Clock`은 리눅스에서 나노초까지 나온다. 절삭 없이 저장하면 (a) 생성 응답의 `createdAt`(나노)과 이후 GET의 `createdAt`(마이크로)이 달라지고, (b) 커서에 실어 보낸 값과 DB 값이 달라져 `created_at < :cursor` 비교에서 같은 행을 다시 반환하거나 건너뛴다.
- 결정: **앱이 만드는 모든 `Instant`는 `Clock`에서 얻은 즉시 `truncatedTo(ChronoUnit.MICROS)`** 한다(`createdAt`, `expiresAt = createdAt + TTL`, `paidAt`, 요청에서 온 `validFrom/validUntil`, 멱등 레코드 시각). 따라서 응답에 나가는 값 = DB에 저장된 값 = 이후 조회 값.
- `expiresAt`은 생성 시 계산해 **컬럼에 저장**한다(TTL 설정이 바뀌어도 기존 주문 불변, R3.5 `expiresAt = createdAt + TTL`).
- 커서는 `createdAt`을 epoch 마이크로초 정수로 인코딩한다(밀리초·문자열 ISO 금지).
- 멱등 재생은 저장된 응답 본문 문자열을 그대로 돌려주므로 시각 포맷이 최초 응답과 바이트 단위로 동일하다.
- 모든 비교(`expires_at <= :now` 등)는 **SQL `now()`가 아니라 앱 `Clock`의 `:now` 파라미터**로 한다(앱·DB 시계 편차 제거).
- 응답 시 오프셋은 입력 오프셋을 보존하지 않고 UTC(`Z`)로 정규화된다(같은 순간). `[ASSUMPTION.]`

---

## 1. 엔드포인트 요약

| # | 메서드 경로 | 성공 | 주요 오류 |
|---|---|---|---|
| 1 | `POST /api/products` | 201 + `Location: /api/products/{id}` | 400 |
| 2 | `GET /api/products/{id}` | 200 | 400(id 타입), 404 |
| 3 | `POST /api/coupons` | 201 + `Location: /api/coupons/{code}` | 400, 409 DUPLICATE_COUPON_CODE |
| 4 | `GET /api/coupons/{code}` | 200 | 404 |
| 5 | `POST /api/orders` | 201 + `Location: /api/orders/{id}` | 400, 422, 409, 404, 409 |
| 6 | `GET /api/orders/{id}` | 200 | 400(id 타입), 404 |
| 7 | `GET /api/orders` | 200 | 400 |
| 8 | `POST /api/orders/{id}/pay` | 200 | 400, 422, 409, 404, 402, 503 |
| 9 | `POST /api/orders/{id}/cancel` | 200 | 400(id 타입), 404, 409, 503 |
| 10 | `POST /api/orders/{id}/ship` | 200 | 400(id 타입), 404, 409 |
| 11 | `POST /api/orders/{id}/deliver` | 200 | 400(id 타입), 404, 409 |

성공 응답 `Content-Type: application/json`. 오류 응답 `application/problem+json`.
`{id}` 경로 변수가 `long`으로 파싱되지 않으면(`/api/products/abc`, long 범위 초과) **400 `VALIDATION_ERROR`**. 0·음수는 정상 파싱 -> 404. `[ASSUMPTION.]` 근거: 형식 오류는 C3의 400 단계. `GET /api/coupons/{code}`는 `code` 형식을 검증하지 않고 없으면 404(소문자·짧은 문자열도 404).

---

## 2. 스키마

### 2.1 상품

`POST /api/products` 요청
```json
{ "name": "string", "price": 1000, "stock": 10 }
```
응답(201, `GET /api/products/{id}` 200과 동일 형태)
```json
{ "id": 1, "name": "string", "price": 1000, "stock": 10, "reserved": 0, "available": 10 }
```
`available = stock - reserved` (파생값, 컬럼 없음).

### 2.2 쿠폰

요청
```json
{ "code": "SALE2026", "type": "RATE", "value": 10, "minOrderAmount": 0, "maxDiscountAmount": null,
  "totalQuantity": 5, "validFrom": "2026-01-01T00:00:00Z", "validUntil": "2027-01-01T00:00:00+09:00" }
```
응답(201 / GET 200)
```json
{ "code": "SALE2026", "type": "RATE", "value": 10, "minOrderAmount": 0, "maxDiscountAmount": null,
  "totalQuantity": 5, "usedCount": 0, "validFrom": "2026-01-01T00:00:00Z", "validUntil": "2026-12-31T15:00:00Z" }
```
`minOrderAmount` 생략/null -> 0 으로 저장·응답. `maxDiscountAmount` 생략/null -> null(제한 없음).

### 2.3 주문 (R3.5 형태 — 생성/단건/목록 원소/pay/cancel/ship/deliver 모두 동일)

```json
{ "id": 7, "userId": "u1", "status": "PENDING_PAYMENT",
  "items": [ { "productId": 1, "quantity": 2, "unitPrice": 1000 } ],
  "couponCode": null, "subtotal": 2000, "discount": 0, "totalPrice": 2000,
  "createdAt": "2026-10-10T03:00:00.123456Z", "expiresAt": "2026-10-10T03:15:00.123456Z", "paidAt": null }
```
- `items` 순서 = 요청 순서(`order_items.id` 오름차순). 잠금 순서(상품 id 오름차순)와는 무관.
- `status` 열거: `PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED` 만 노출. 결제/환불 진행 중 표시는 내부 컬럼이며 상태 열거에 추가하지 않는다.
- `paidAt`: 결제 승인 시각. `REFUNDED`/`SHIPPED`/`DELIVERED`에서도 유지. `PENDING_PAYMENT`/`PAYMENT_FAILED`/`EXPIRED`/`CANCELLED`는 null. `[ASSUMPTION.]`

### 2.4 주문 생성 요청 / 결제 요청

```
POST /api/orders
X-User-Id: u1            (공백 아닌 1~50자)
Idempotency-Key: k-123   (1~64자)
{ "items": [ { "productId": 1, "quantity": 2 } ], "couponCode": "SALE2026" }   // couponCode 생략/null 가능

POST /api/orders/{id}/pay
Idempotency-Key: k-456   (1~64자)
{ "cardToken": "tok_abc" }                                                     // 공백 불가
```
`couponCode`는 형식 검증 없이 값이 있으면 그대로 조회한다(없으면 404; 빈 문자열 포함). `[ASSUMPTION.]` R3.2는 `couponCode` 형식 400을 규정하지 않는다.
`cardToken`은 DB·로그에 저장하지 않는다(PG로만 전달). 응답에도 없다.
`pay` 요청의 `X-User-Id`는 선택이며 검증하지 않는다(인가 범위 밖). 있으면 멱등 지문에 포함한다. `[ASSUMPTION.]`

### 2.5 주문 목록

`GET /api/orders?userId=&status=&size=&cursor=` -> `{ "content": [ <3절 주문>... ], "nextCursor": "<opaque>" | null }`

---

## 3. 오류 응답 (R11)

모든 오류(우리 애플리케이션이 응답하는 것 전부)는 `Content-Type: application/problem+json`, 본문:
```json
{ "type": "urn:problem-type:order-payment:validation-error", "title": "Validation Error", "status": 400,
  "detail": "price: must be greater than or equal to 1", "code": "VALIDATION_ERROR",
  "instance": "/api/products", "errors": [ { "field": "price", "message": "..." } ] }
```
- 필수: `type, title, status, detail, code`. `instance`(요청 경로)·`errors`(검증 위반 목록, 400에만)는 추가 확장. `type`은 `urn:problem-type:order-payment:{code를 소문자-kebab으로}`.
- `ResponseEntity.contentType(APPLICATION_PROBLEM_JSON)`을 명시해 `Accept`와 무관하게 항상 `application/problem+json`. `ResponseEntityExceptionHandler`를 상속한 `@RestControllerAdvice` 한 곳에서 처리하고 `handleExceptionInternal`을 재정의해 프레임워크 예외도 같은 포맷·`code`를 갖게 한다.

| status | code | title | 발생 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Validation Error | Bean Validation 위반, 서비스 검증(RATE 범위·기간·중복 productId), JSON 파싱 실패/타입 불일치, 필수 헤더 누락/길이 위반, 잘못된 쿼리 파라미터(size, status, cursor), 잘못된 경로 변수 타입 |
| 402 | `PAYMENT_DECLINED` | Payment Declined | PG 거절 |
| 404 | `PRODUCT_NOT_FOUND` | Product Not Found | 상품 id 없음(상품 조회, 주문 항목) |
| 404 | `COUPON_NOT_FOUND` | Coupon Not Found | 쿠폰 code 없음 |
| 404 | `ORDER_NOT_FOUND` | Order Not Found | 주문 id 없음 |
| 409 | `INSUFFICIENT_STOCK` | Insufficient Stock | available 부족 |
| 409 | `COUPON_NOT_APPLICABLE` | Coupon Not Applicable | 기간/최소금액/사용자 중복 |
| 409 | `COUPON_EXHAUSTED` | Coupon Exhausted | usedCount = totalQuantity |
| 409 | `DUPLICATE_COUPON_CODE` | Duplicate Coupon Code | 쿠폰 code 중복 |
| 409 | `INVALID_STATE` | Invalid State | 상태 전이 불가, 만료 경과 결제, 결제/환불 진행 중 |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Idempotency In Progress | 같은 키의 처리 중 요청 |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | Idempotency Key Mismatch | 같은 키·다른 요청 |
| 503 | `PAYMENT_GATEWAY_UNAVAILABLE` | Payment Gateway Unavailable | PG 5xx/연결 실패/2초 초과/해석 불가 응답 |

계약 밖 오류(존재하지 않는 경로 404, 지원 안 하는 메서드 405 + `Allow` 헤더, 415, 406, 500)도 `application/problem+json` + `type/title/status/detail`로 응답하되, 표에 맞는 code가 없으므로 **`code` 필드는 생략**한다. `[ASSUMPTION.]` ("code는 표의 값만"을 지키기 위해 임의 값을 만들지 않는다.) 500은 내부 정보를 `detail`에 노출하지 않는다.

예외 -> 응답 매핑(구현 지침): `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `MissingRequestHeaderException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`, `TypeMismatchException` -> 400 `VALIDATION_ERROR`. 도메인 예외(`code`, `status` 보유)는 표대로.

---

## 4. 검증 규칙과 경계값

Bean Validation(구문·범위)과 서비스 검증(필드 간·DB 의존)을 나눈다. 모두 400 단계이며 DB를 건드리기 전에 끝난다.

### 4.1 상품 (R1.2) — Bean Validation

| 필드 | 규칙 | 통과 | 400 |
|---|---|---|---|
| name | `@NotNull @NotBlank @Size(max=100)` | 1자, 100자 | 누락, `""`, `"   "`, 101자, 숫자/비문자열 |
| price | `@NotNull @Min(1) @Max(10_000_000)` Long | 1, 10,000,000 | 0, -1, 10,000,001, 3,000,000,000, `10.5`, `"100"`, 누락 |
| stock | `@NotNull @Min(0) @Max(1_000_000)` Long | 0, 1,000,000 | -1, 1,000,001, 소수, 문자열, 누락 |

`name` 길이는 `String.length()` 기준(UTF-16 단위)이며 저장 시 trim하지 않는다. `[ASSUMPTION.]` price·stock은 필수.

### 4.2 쿠폰 (R2.2)

| 필드 | 규칙 | 통과 | 400 |
|---|---|---|---|
| code | `@NotNull @Pattern("[A-Z0-9]{4,20}")` (전체 일치) | 4자 `AB12`, 20자 | 3자, 21자, 소문자 `abcd`, 특수문자·공백·개행, 누락 |
| type | enum `FIXED`/`RATE`, 대소문자 구분 | `FIXED`, `RATE` | `fixed`, `PERCENT`, 숫자, 누락 |
| value | `@NotNull @Min(1)` Long + **서비스**: RATE면 `<= 100` | FIXED 1~Long.MAX, RATE 1~100 | 0, RATE 101, 음수 |
| minOrderAmount | nullable -> 0, `@Min(0)` | 생략, 0 | -1 |
| maxDiscountAmount | nullable, 값이 있으면 `@Min(1)` | 생략/null, 1 | 0, -1 |
| totalQuantity | `@NotNull @Min(1)` Long | 1 | 0, 누락 |
| validFrom/validUntil | `@NotNull` 문자열, ISO 오프셋 파싱 가능 + **서비스**: `validFrom < validUntil`(마이크로초 절삭 후 엄격 부등호) | from < until | from == until, from > until, 오프셋 없음, 형식 오류 |

중복 `code`는 400 검증 통과 후 409 `DUPLICATE_COUPON_CODE` (400 > 409).

### 4.3 주문 생성 (R3.2)

| 대상 | 규칙 | 400 |
|---|---|---|
| `X-User-Id` | 필수, `@NotBlank @Size(max=50)` | 누락, 공백만, 51자 |
| `Idempotency-Key` | 필수, 길이 1~64 (공백 체크 없음, 원문 사용) `[ASSUMPTION.]` | 누락, 빈 값, 65자 |
| items | `@NotNull @Size(min=1,max=20)`, 원소 `@NotNull @Valid` | 누락, `[]`, 21개, null 원소 |
| items[].productId | `@NotNull` Long (0·음수는 형식상 유효 -> 404) | 누락 |
| items[].quantity | `@NotNull @Min(1) @Max(1000)` | 0, 1001, 누락, 소수 |
| items 전체 | **서비스**: 같은 productId 중복 불가 | 중복 |
| couponCode | 생략/null 가능, 형식 검증 없음 | — |

### 4.4 결제 (R5.1, R4.1)
`Idempotency-Key` 필수 1~64자, 본문 `{cardToken}` `@NotNull @NotBlank`(누락·`""`·`"  "`·비문자열 -> 400). 본문 자체가 없거나 JSON이 깨졌어도 400.

### 4.5 목록 쿼리 (R9.2, R9.3) — 컨트롤러에서 문자열로 받아 직접 파싱

| 파라미터 | 규칙 | 400 |
|---|---|---|
| userId | 선택. 값이 있으면 공백 아님 (`userId=` 빈 값/공백 -> 400) `[ASSUMPTION.]` | 빈 값 |
| status | 선택. `OrderStatus` 이름과 정확히 일치(대소문자 구분) | `foo`, `paid`, 빈 값 |
| size | 선택, 기본 20, 정수 1~100 | 0, -1, 101, `abc`, `1.5`, 빈 값, `size=1&size=2` |
| cursor | 선택. 8절 형식에 맞아야 함 | 형식 불일치, 빈 값 |

경계: size 0 -> 400, 1 -> OK, 100 -> OK, 101 -> 400.

---

## 5. 주문 생성 `POST /api/orders` — 검사 순서 (C3)

각 단계에서 실패하면 즉시 해당 응답으로 끝나고 이후 단계는 실행하지 않는다.

| 단계 | 내용 | 실패 응답 |
|---|---|---|
| 1 | **400**: 헤더(X-User-Id, Idempotency-Key)·본문 구문·items 중복·경계값 검증 | 400 VALIDATION_ERROR |
| 2 | **멱등 키**: `(ORDER_CREATE, key)`에 대한 `begin`. 지문 불일치 -> 422. 같은 지문 + COMPLETED -> **재생**(저장된 상태코드·본문·Location). 같은 지문 + IN_PROGRESS -> 409 `IDEMPOTENCY_IN_PROGRESS`. 없으면 IN_PROGRESS로 선점하고 진행 | 422 / 409 / 재생 |
| 3 | (진행 시) 만료 대상 주문 지연 정리 `expireDue()` (6절) — 방금 만료된 예약이 재고에 반영되도록 | — |
| 4 | **404**: 모든 `productId` 존재 확인(상품 먼저), 이어서 `couponCode` 존재 확인. 여러 개 없으면 가장 작은 productId를 보고 | 404 PRODUCT_NOT_FOUND / COUPON_NOT_FOUND |
| 5 | **409 재고**: 상품 id 오름차순으로 조건부 증가 `reserved += q WHERE stock - reserved >= q`. 0행이면 | 409 INSUFFICIENT_STOCK |
| 6 | **409 쿠폰** (쿠폰이 있을 때만): 쿠폰 행 `FOR UPDATE` 잠금 후 순서대로 (a) 요청 시각 `[validFrom, validUntil)` 밖 (b) `subtotal < minOrderAmount` (c) 같은 사용자가 이 쿠폰을 사용 중인 주문 존재 -> `COUPON_NOT_APPLICABLE`; 그 다음 (d) `usedCount == totalQuantity` -> `COUPON_EXHAUSTED`; 통과하면 `usedCount += 1` | 409 |
| 7 | 할인 계산(5.1), 주문·항목 INSERT(`createdAt`, `expiresAt`), 멱등 레코드를 COMPLETED(201, 본문, Location)로 갱신, **커밋** | 201 |

- 5·6·7은 **하나의 DB 트랜잭션**이다(R3.4). 어느 단계든 예외면 전체 롤백(예약·`usedCount` 포함) 후 멱등 레코드 해제(R4.4).
- 6의 (a)~(d) 순서 `[ASSUMPTION.]`: R2.5가 "조건 위반 -> NOT_APPLICABLE, 소진 -> EXHAUSTED"로 적었고, 결정적 조건(기간·최소금액·중복 사용)을 상태 의존 조건(소진)보다 먼저 판정한다.
- C3 예시: 상품 404 + 재고 부족 -> 404. 쿠폰 404 + 재고 부족 -> 404(4단계가 5단계보다 앞). 재고 부족 + 쿠폰 기간 밖 -> `INSUFFICIENT_STOCK`(5 < 6). 쿠폰 소진 + 같은 사용자 중복 사용 -> `COUPON_NOT_APPLICABLE`. 본문 오류 + 키 재사용 -> 400(1 < 2). 키 불일치 + 상품 없음 -> 422(2 < 4).
- 요청 시각 = 6단계에서 쿠폰 행 잠금을 얻은 직후 `Clock`에서 읽은 단일 `Instant`(마이크로초 절삭) 하나를 기간 판정과 `createdAt` 양쪽에 쓴다(`createdAt`을 가능한 한 커밋에 가깝게 해 9절의 커밋 순서 역전 창을 줄인다).
- 쿠폰이 없는 주문은 6단계를 건너뛴다.

### 5.1 할인 계산 (R2.4, 의사코드)

```
subtotal = Σ unitPrice × quantity                 // long, 최대 2e11
if coupon == null: discount = 0
else:
  raw = (type == FIXED) ? value
                        : Math.floorDiv(subtotal * value, 100)   // value<=100 -> 최대 2e11*100 = 2e13 < 9.22e18, 오버플로 없음
  d = raw
  if maxDiscountAmount != null: d = min(d, maxDiscountAmount)    // 1) maxDiscount 상한
  d = min(d, subtotal)                                           // 2) subtotal 상한 (마지막)
  discount = d
totalPrice = subtotal - discount                                 // >= 0
```
`subtotal`·`discount` 계산에 `Math.multiplyExact/addExact` 사용(방어). 최소금액 판정은 할인 전 `subtotal` 기준. FIXED `value`가 Long.MAX 근처여도 `min`만 쓰므로 안전. `unitPrice` = 5단계 이전에 읽은 상품 `price` 스냅샷(상품 수정 기능이 없어 불변).

---

## 6. 결제 `POST /api/orders/{id}/pay` — 검사 순서 (C3)

| 단계 | 내용 | 실패 응답 |
|---|---|---|
| 1 | **400**: 경로 `id` 타입, `Idempotency-Key` 1~64자, 본문 `cardToken` | 400 |
| 2 | **멱등 키**: `(ORDER_PAY, key)` begin. 지문 = `X-User-Id`(없으면 빈 값) + `POST /api/orders/{id}/pay`(경로에 orderId 포함) + `cardToken`. 불일치 422 / COMPLETED 재생 / IN_PROGRESS 409 | 422 / 409 / 재생 |
| 3 | **404 + 409 (짧은 트랜잭션 TX-A, 주문 행 `FOR UPDATE`)**: 주문 없음 -> 404. 지연 만료 적용(`PENDING_PAYMENT`이고 `expiresAt <= now`이고 진행 중 아님이면 EXPIRED로 전이 후 커밋). 이후 `status != PENDING_PAYMENT` 또는 `expiresAt <= now` 또는 **다른 결제 진행 중** -> 409 `INVALID_STATE` | 404 / 409 |
| 4a | `totalPrice == 0` (R5.7): PG 호출 없음. **같은 TX-A**에서 곧바로 승인 처리(아래 승인 효과) -> 200 | 200 |
| 4b | `totalPrice > 0`: TX-A가 주문에 "게이트웨이 호출 진행 중" 표지(`gateway_call_started_at = now`)를 찍고 **커밋**(행 락 해제, DB 커넥션 반납) | — |
| 5 | **PG 호출** (트랜잭션 밖): `POST {PAYMENT_GATEWAY_URL}/v1/payments`, 헤더 `Idempotency-Key` = 클라이언트 키 그대로, 본문 `{orderId, amount: totalPrice, cardToken}`. 전체 2초 데드라인 | — |
| 6 | **결과 반영 (TX-B, 주문 행 `FOR UPDATE`, 표지가 내 것인지 확인)** | 아래 |

PG 결과별 TX-B 효과:
- **APPROVED**: `status=PAID`, `paid_at=now`, `payment_id` 저장, 표지 해제. 각 상품(id 오름차순) `stock -= q`, `reserved -= q`. 멱등 레코드 COMPLETED(200, 본문). -> **200**
- **DECLINED**: `status=PAYMENT_FAILED`, 표지 해제. 상품(id 오름차순) `reserved -= q`, 쿠폰 `usedCount -= 1`(쿠폰 사용 복원). 멱등 레코드 해제. -> **402 PAYMENT_DECLINED**
- **장애**(5xx, 연결 실패, 2초 초과, 4xx·해석 불가 본문·`paymentId` 없음 `[ASSUMPTION.]`): 표지만 해제, 주문·재고·쿠폰 불변. 멱등 레코드 해제. -> **503 PAYMENT_GATEWAY_UNAVAILABLE**
- TX-B에서 표지가 내 것이 아님(10초 이상 지연된 뒤 다른 요청이 인수한 극히 드문 경우): 상태 변경 없이 409 `INVALID_STATE`, 오류 로그.

설계 결정 요약:
- **PG 호출 동안 DB 락·커넥션을 잡지 않는다.** 주문 행 락을 2초간 잡으면 동시 결제 요청이 커넥션 풀(기본 10)을 점유한 채 대기해 풀 고갈 위험이 있다. 대신 내부 표지 컬럼으로 직렬화한다. 새 `status` 값은 만들지 않으므로 노출 상태 열거는 feature.md 그대로이다.
- **R10.5**: 같은 주문에 동시 결제(키 다름)가 오면 TX-A의 행 락 때문에 순차 처리되고, 첫 요청이 표지를 찍은 뒤의 요청은 `INVALID_STATE`(409)를 받는다. PG 요청은 최대 1회, 성공 응답은 최대 1건. (첫 요청이 503으로 끝난 뒤 도착한 요청은 새 시도로 정상 진행 — 그래도 동시에 둘이 PG로 나가지 않는다.)
- **만료 vs 결제 경합**: 판정 시점은 TX-A(`expiresAt > now`). TX-A를 통과한 결제는 PG 결과가 `expiresAt` 이후에 도착해도 **그대로 반영**한다(돈이 이미 승인되었으므로). 표지가 있는 동안(신선, `in-flight-timeout` 10초 미만) 만료 스위퍼는 그 주문을 건드리지 않는다. 결과적으로 `expiresAt` 직전에 시작한 결제가 PG에서 2초를 쓰면 EXPIRED 반영이 최대 약 2초 + 스위퍼 주기 늦을 수 있다(R6.2 한계, 8절). `[ASSUMPTION.]`
- **PG 타임아웃**: `java.net.http.HttpClient`(HTTP/1.1 강제, 리다이렉트 없음, 자동 재시도 없음)의 `sendAsync(...).orTimeout(2, SECONDS)`로 연결+송신+응답 본문 수신 **전체**를 2초 데드라인에 묶고, 만료 시 future를 취소한다. `connectTimeout(2s)`·`HttpRequest.timeout(2s)`도 보조로 설정. Spring `RestClient`의 connect/read 타임아웃 합산(최대 4초) 방식은 쓰지 않는다. PG 응답 파싱용 ObjectMapper는 앱 기본(strict)과 별도의 관대한 인스턴스 사용(`paymentId`가 숫자여도 문자열로 수용).
- **재시도 안전성**: 크래시 등으로 클라이언트가 같은 키로 재요청하면 같은 `Idempotency-Key`가 PG로 가므로 PG가 최초 결과를 돌려준다(이중 결제 없음).
- PG 요청 `orderId`는 JSON 숫자(주문 id), `amount`는 JSON 숫자(`totalPrice`). `[ASSUMPTION.]` (계약에 타입이 없음)

---

## 7. 취소·배송

### 7.1 취소 `POST /api/orders/{id}/cancel`
| 단계 | 내용 | 응답 |
|---|---|---|
| 1 | 경로 `id` 타입 400 | 400 |
| 2 | 주문 행 `FOR UPDATE`. 없음 | 404 ORDER_NOT_FOUND |
| 3 | 지연 만료 적용(만료 시각이 지났고 진행 중 표지 없으면 EXPIRED로 전이) 후 상태 판정 | — |
| 4 | `PENDING_PAYMENT`(표지 없음): `CANCELLED`, 상품 `reserved -= q`(id 오름차순), 쿠폰 `usedCount -= 1`. 한 트랜잭션 | 200 |
| 5 | `PAID`(표지 없음), `totalPrice > 0`: 표지 찍고 커밋 -> PG `POST /v1/payments/{paymentId}/refund`(2초 데드라인, 트랜잭션 밖) -> 성공(200 + `status=REFUNDED`): 주문 `REFUNDED`, 상품 `stock += q`(id 오름차순), 쿠폰 `usedCount -= 1`, 표지 해제 | 200 |
| 5' | 위 환불 장애(5xx·연결 실패·2초·해석 불가): 표지만 해제, 주문·재고·쿠폰 불변 | 503 PAYMENT_GATEWAY_UNAVAILABLE |
| 6 | `PAID`이고 `totalPrice == 0`: PG에 결제가 없었으므로 환불 호출 없이 같은 트랜잭션에서 `REFUNDED`로 처리 `[ASSUMPTION.]` | 200 |
| 7 | 그 외 상태(`SHIPPED`, `DELIVERED`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`) 또는 결제/환불 진행 중 표지가 신선한 주문 | 409 INVALID_STATE |

`PENDING_PAYMENT` 주문에 결제가 진행 중이면 취소는 409(돈이 나가는 중인 주문을 취소해 환불 필요 상태를 만들지 않기 위함). `[ASSUMPTION.]`
환불 호출에는 `Idempotency-Key`를 보내지 않는다(PG 계약에 없음). `payment_id`는 `orders.payment_id`에 저장되어 있다(02 문서).

### 7.2 배송 `ship` / `deliver`
주문 행 `FOR UPDATE` -> 없음 404 -> `ship`: `PAID`(표지 없음)이면 `SHIPPED`, `deliver`: `SHIPPED`이면 `DELIVERED`, 그 외 409 INVALID_STATE. 환불 진행 중인 `PAID`의 `ship`은 409. 응답 200 + 주문 본문.

### 7.3 상태 전이 표 (현재 상태 × 동작)

"진행 중" = 신선한 게이트웨이 호출 표지. `expire`는 시스템 동작(스케줄러·지연 만료).

| 현재 상태 | pay | cancel | expire | ship | deliver |
|---|---|---|---|---|---|
| PENDING_PAYMENT (`expiresAt` 이전, 표지 없음) | 승인 -> PAID 200 / 거절 -> PAYMENT_FAILED 402 / PG 장애 503 불변 | -> CANCELLED 200 | (해당 없음) | 409 | 409 |
| PENDING_PAYMENT (`expiresAt` 경과, 표지 없음) | 409 INVALID_STATE (+ 지연 만료로 EXPIRED 전이) | 지연 만료 후 EXPIRED이므로 409 | -> EXPIRED (복원) | 409 | 409 |
| PENDING_PAYMENT (결제 진행 중) | 409 | 409 | 건너뜀(결제 결과 후 판단) | 409 | 409 |
| PAID (표지 없음) | 409 | -> REFUNDED 200 / PG 장애 503 불변 | 무시(대상 아님) | -> SHIPPED 200 | 409 |
| PAID (환불 진행 중) | 409 | 409 | 무시 | 409 | 409 |
| SHIPPED | 409 | 409 | 무시 | 409 | -> DELIVERED 200 |
| DELIVERED | 409 | 409 | 무시 | 409 | 409 |
| PAYMENT_FAILED | 409 | 409 | 무시 | 409 | 409 |
| EXPIRED | 409 | 409 | 무시 | 409 | 409 |
| CANCELLED | 409 | 409 | 무시 | 409 | 409 |
| REFUNDED | 409 | 409 | 무시 | 409 | 409 |

모든 동작에서 주문 id가 없으면 404가 먼저(400 타입 검증과 멱등 검사 이후). 자원 복원 규칙: CANCELLED/EXPIRED/PAYMENT_FAILED -> `reserved -= q` + 쿠폰 복원; REFUNDED -> `stock += q` + 쿠폰 복원; PAID 승인 -> `stock -= q`, `reserved -= q`.

---

## 8. 만료 (R6)

- `ORDER_PAYMENT_TTL`(ISO-8601 Duration, 기본 `PT15M`, 예 `PT3S`) -> `order.payment-ttl`. `expiresAt = createdAt + TTL`(마이크로초 절삭).
- **스케줄러 + 지연 만료 병행** (결정):
  1. 스케줄러: `@Scheduled(fixedDelay = 200ms)`(`order.expiry.sweep-interval`, 기본 `PT0.2S`). 만료 후보 id를 조회(`status='PENDING_PAYMENT' AND expires_at <= :now AND (표지 없음 OR 표지 오래됨)`, 최대 100건)하고, **주문마다 별도 짧은 트랜잭션**에서 행을 `FOR UPDATE`로 잠그고 조건을 재확인한 뒤 EXPIRED 전이 + 자원 복원. 예외는 삼키고 로그(스케줄러가 죽지 않게).
  2. 지연 만료 `expireDue()`: `GET /api/products/{id}`, `GET /api/coupons/{code}`, `GET /api/orders/{id}`, `GET /api/orders`, 그리고 주문 생성 3단계 앞에서 같은 로직을 한 번 실행(요청 스레드, 읽기 트랜잭션 **밖**의 독립 트랜잭션). 스케줄러가 지연돼도 조회 시점에 자가 치유되고, `expiresAt` 직후의 생성이 풀린 재고를 본다. 비용은 부분 인덱스 범위 스캔 1회.
  3. `cancel`·`pay`는 주문 행 락 안에서 같은 조건으로 해당 주문만 지연 만료 처리 후 상태를 판정(경과한 주문의 cancel은 409, pay는 409).
- 만료·결제·취소 경합 일관성: 모든 상태 전이는 **주문 행 `FOR UPDATE` 아래에서 상태를 재확인**한 뒤 수행하므로 둘 중 하나만 성공한다. 락 순서는 항상 주문 행 -> 상품(id 오름차순) -> 쿠폰 행(02 문서 락 순서). 데드락 없음.
- 반영 지연: 스케줄러 주기 200ms + 처리 시간. R6.2(2초)에 대해 충분한 여유. 한계: 결제 진행 표지가 있는 주문은 결제 결과가 나올 때까지(최대 약 2초) 만료가 보류된다.

---

## 9. 목록 `GET /api/orders` (R9)

- 정렬 `createdAt DESC, id DESC`. 필터 `userId`, `status`는 AND. 둘 다 없으면 전체.
- 쿼리: `WHERE [user_id=?] [AND status=?] [AND (created_at, id) < (?, ?)] ORDER BY created_at DESC, id DESC LIMIT size+1` (네이티브 행 비교 사용, `(:p IS NULL OR ...)` 패턴은 쓰지 않고 조건 문자열을 동적으로 조립). `size+1`개를 읽어 초과분이 있으면 `nextCursor`를 마지막 반환 원소로 만들고, 없으면 `nextCursor = null`.
- **커서**: `base64url(no padding)("v1:" + createdAtEpochMicros + ":" + id)`. 불투명 값으로 취급하되 디코딩 실패(문자셋·패딩·접두어·정수 파싱·음수·long 범위)는 400 `VALIDATION_ERROR`. 커서에는 필터를 싣지 않는다(필터는 매 요청 쿼리 파라미터를 따른다).
- 원소 형태는 R3.5와 동일(같은 매퍼). N+1 방지: 페이지 쿼리로 `orders`만 읽고 `items`는 `@BatchSize(100)`(전역 `hibernate.default_batch_fetch_size=100`)으로 IN 쿼리 1회에 로딩. `JOIN FETCH`+페이징은 쓰지 않는다(메모리 페이징). 쿠폰 코드는 `orders.coupon_code`에 비정규화되어 조인이 필요 없다.
- **R9.5 결정 — 커밋 순서 역전 처리**: 키셋 키 `(created_at, id)`는 행 생성 후 **불변**이고 전순서(total order)이다. 첫 페이지 시점에 커밋되어 있던 주문은 이후 모든 쿼리에서도 보이며 각자 정해진 키 위치에서 정확히 한 번 나온다(커서 경계 `<`로 페이지가 겹치지 않음). 새 주문이 끼어들어도 이 성질은 깨지지 않는다. 커밋 순서가 `created_at`/`id` 순서와 어긋나서(created_at이 더 이른 트랜잭션이 나중에 커밋) 생기는 부작용은 "그 시점에 아직 존재하지 않던 주문이 이후 페이지에 나타날 수 있음"뿐이며 R9.5는 이를 금지하지 않는다. 따라서 `created_at` 단조 증가 강제(전역 락·시퀀스)는 도입하지 않는다. 단, 어긋남 창을 줄이기 위해 `createdAt`을 생성 트랜잭션의 마지막 직전(5절 7단계 직전, 쿠폰·재고 락 획득 후)에 찍는다. 마이크로초 정밀도 절삭 + `id` 타이브레이커로 같은 시각 충돌도 처리한다.
- **status 필터 중 상태가 변하는 주문**: 필터는 각 페이지 쿼리 시점의 현재 상태로 평가한다(필터와 다른 상태로 변한 주문은 이후 페이지에서 빠질 수 있고, 새로 필터에 맞게 된 주문은 키가 커서 이전이면 추가될 수 있음). 필터 없는 목록과 `userId`만 쓰는 목록은 상태 변화와 무관하게 정확히 한 번 보장.
- 인덱스는 02 문서(`(created_at DESC, id DESC)`, `(user_id, created_at DESC, id DESC)`, `(status, created_at DESC, id DESC)`).
- 목록 호출도 `expireDue()` 후 읽는다(만료 반영).

---

## 10. 설정 (C4)

`src/main/resources/application.yml` (Boot relaxed binding이 `SPRING_DATASOURCE_*`, `SERVER_PORT`를 자동 매핑하므로 환경 변수만 주면 덮어써진다. 기본값은 yml에 둔다.)

| 프로퍼티 | 환경 변수 | 기본값 |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/order` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `order` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | `order` |
| `server.port` | `SERVER_PORT` | `8080` |
| `payment.gateway.url` | `PAYMENT_GATEWAY_URL` (`${PAYMENT_GATEWAY_URL:http://localhost:9090}`) | `http://localhost:9090` |
| `order.payment-ttl` | `ORDER_PAYMENT_TTL` (`${ORDER_PAYMENT_TTL:PT15M}`) | `PT15M` |
| `payment.gateway.timeout` | — | `PT2S` (요구사항 고정값, 테스트용으로만 변경) |
| `order.expiry.sweep-interval` | — | `PT0.2S` |
| `order.expiry.in-flight-timeout` | — | `PT10S` (게이트웨이 호출 표지 유효 시간) |
| `order.idempotency.in-progress-timeout` | — | `PT30S` |
| `spring.jpa.open-in-view` | — | `false` |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` (Flyway가 스키마 소유) |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | — | `UTC` |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | — | `100` |
| `spring.datasource.hikari.maximum-pool-size` | — | `20` (동시성 테스트 20 스레드 대응) |
| `spring.flyway.enabled` | — | `true` |

`order.payment-ttl`는 `@ConfigurationProperties`(`Duration`, 양수 검증)로 바인딩. 잘못된 값이면 기동 실패. Jackson 프로퍼티는 0.1절.
Testcontainers 테스트: `@ServiceConnection static PostgreSQLContainer`(또는 `@DynamicPropertySource`로 `spring.datasource.*`), 가짜 PG 서버 URL은 `@DynamicPropertySource`로 `payment.gateway.url`, 만료 테스트는 `order.payment-ttl=PT3S` 등으로 주입. 가짜 PG는 JDK `HttpServer` 등으로 구현 가능(build.gradle에 WireMock 없음 — 필요 시 test-writer가 의존성을 추가).
`Clock` 빈(`Clock.systemUTC()`)을 주입해 서비스가 사용한다.

---

## 11. 멱등성 외부 계약 요약 (R4) — 메커니즘은 02 문서

- 대상: `POST /api/orders`(`ORDER_CREATE`), `POST /api/orders/{id}/pay`(`ORDER_PAY`). 키 공간 `(endpoint, key)`로 독립 — 같은 문자열 키를 두 엔드포인트가 각각 써도 충돌하지 않는다.
- 요청 지문 = SHA-256(`v1|X-User-Id|METHOD|정규화 경로|정규화 본문`). 정규화 본문: 생성은 `items`(요청 순서의 `productId:quantity` 나열)+`couponCode`(없으면 `-`), 결제는 `cardToken`. 검증(1단계) 통과 후에 계산하므로 JSON 공백·필드 순서·`couponCode` null/생략 차이는 같은 요청으로 본다. 결제 경로에는 `orderId`가 들어간다.
- 판정(2단계): 지문 불일치(상태 무관) -> 422; 일치 + COMPLETED -> 재생; 일치 + IN_PROGRESS(신선) -> 409 IN_PROGRESS; 일치 + IN_PROGRESS(30초 초과, 크래시 잔재) -> 인수해 재처리.
- 재생 = 저장된 **상태코드 + 본문 문자열 + Content-Type(`application/json`) + Location**을 그대로. 현재 주문 상태로 다시 렌더링하지 않는다. 최초 응답도 저장 문자열 그대로 내보낸다(바이트 동일).
- 2xx(생성 201, 결제 200)만 COMPLETED로 저장. 4xx/5xx(402 포함)는 레코드를 삭제해 같은 요청으로 재시도 가능.
- 동시 같은 키: 실제 처리 1회, 나머지는 409 `IDEMPOTENCY_IN_PROGRESS` 또는(처리가 이미 끝났다면) 재생.

---

## 12. [ASSUMPTION.] 목록

1. 알 수 없는 JSON 필드는 무시한다(요구사항에 거부 규정 없음).
2. 경로 변수 `id` 타입 오류(`/api/products/abc`) -> 400 VALIDATION_ERROR. 0·음수 id는 404.
3. 계약 밖 오류(경로 404, 405, 415, 406, 500)는 Problem Details이되 `code` 생략.
4. 응답 시각은 UTC(`Z`)로 정규화되어 입력 오프셋은 보존되지 않는다(동일 순간).
5. `price`·`stock` 필수. `couponCode`는 형식 검증 없음(없으면 404).
6. `Idempotency-Key`는 길이만 검증(공백 문자열 허용, 단 헤더 값이 비면 400). `X-User-Id`는 pay에서 선택·미검증.
7. 상품 404가 쿠폰 404보다 먼저 보고된다. 쿠폰 409 내부 순서는 NOT_APPLICABLE(기간 -> 최소금액 -> 사용자 중복) -> EXHAUSTED.
8. 결제 진행 중 표지는 내부 컬럼이며, 진행 중에는 pay/cancel/ship이 409 INVALID_STATE, 만료 스위퍼는 건너뜀. `TX-A`를 통과한 결제는 `expiresAt` 이후에 승인되어도 반영한다(R6.2 최대 약 2초 보류).
9. PG의 4xx·해석 불가 응답·`paymentId` 누락은 장애(503)로 취급. PG 요청 `orderId`/`amount`는 JSON 숫자.
10. `totalPrice == 0`으로 PAID가 된 주문의 취소는 PG 환불 호출 없이 REFUNDED.
11. `paidAt`은 REFUNDED/SHIPPED/DELIVERED에서도 유지.
12. 목록 `userId=` 빈 값, `size=` 빈 값, `cursor=` 빈 값은 400.
13. `name` 길이는 UTF-16 `length()` 기준.
14. Location은 상대 경로(`/api/products/1`).

---

## 13. 요구사항 추적표

| id | 충족 위치 |
|---|---|
| C1 | 0절, 5.1 |
| C2 | 0절, 0.1, 0.2 |
| C3 | 5절(생성), 6절(결제), 7절(취소 등), 11절 |
| C4 | 10절 |
| R1.1 | 1절 #1, 2.1 |
| R1.2 | 4.1 |
| R1.3 | 1절 #2, 2.1 |
| R1.4 | 2.1(`available` 파생), 7.3 마지막 문단(재고 의미) |
| R2.1 | 1절 #3, 2.2 |
| R2.2 | 4.2, 3절(409 DUPLICATE_COUPON_CODE), 정합 요약 B |
| R2.3 | 1절 #4, 2.2 |
| R2.4 | 5.1 |
| R2.5 | 5절 6단계 (a)~(d) |
| R2.6 | 5절 6단계, 6절(DECLINED), 7.1, 7.3, 8절 |
| R3.1 | 1절 #5, 2.4, 5절 |
| R3.2 | 4.3 |
| R3.3 | 5절 4·5단계 |
| R3.4 | 5절(5~7 단일 트랜잭션) |
| R3.5 | 2.3, 9절(목록 동일 형태) |
| R4.1 | 4.4, 11절, 5절 2단계 |
| R4.2 | 11절, 5절 2단계, 6절 2단계 |
| R4.3 | 11절, 3절(422) |
| R4.4 | 11절, 5절 끝, 6절 TX-B |
| R4.5 | 11절 |
| R5.1 | 1절 #8, 2.4, 4.4 |
| R5.2 | 6절 3단계 |
| R5.3 | 6절 5단계 |
| R5.4 | 6절 APPROVED |
| R5.5 | 6절 DECLINED |
| R5.6 | 6절 장애 |
| R5.7 | 6절 4a |
| R6.1 | 8절 |
| R6.2 | 8절 |
| R7.1 | 1절 #9, 7.1 |
| R7.2 | 7.1 4단계 |
| R7.3 | 7.1 5·5'·6단계 |
| R7.4 | 7.1 7단계, 7.3 |
| R8.1 | 7.2 |
| R8.2 | 7.2, 7.3 |
| R9.1 | 2.5, 9절 |
| R9.2 | 4.5, 9절 |
| R9.3 | 4.5, 9절(커서) |
| R9.4 | 9절 |
| R9.5 | 9절(R9.5 결정) |
| R10.1 | 5절 5단계 (메커니즘: 02 문서 락 전략) |
| R10.2 | 5절 6단계 |
| R10.3 | 5절 6단계 (c) |
| R10.4 | 5절 5단계(상품 id 오름차순), 02 문서 락 순서 |
| R10.5 | 6절 설계 결정 |
| R11.1 | 3절 |
| R11.2 | 3절 |
| R11.3 | 3절 code 표 |

---

## 정합 요약 (구현·검증 기준표 — 02_db_design.md는 이 표를 참조만 한다)

### A. 응답 필드 ↔ 컬럼 대응

| 응답/요청 필드 | 테이블.컬럼 | 타입(JSON / Java / DB) | 요청 필수 ↔ nullable |
|---|---|---|---|
| product.id | products.id | number / long / BIGINT identity | 서버 생성 |
| product.name | products.name | string / String / VARCHAR(100) NOT NULL | 필수 ↔ NOT NULL |
| product.price | products.price | number / long / BIGINT NOT NULL | 필수 ↔ NOT NULL |
| product.stock | products.stock | number / long / BIGINT NOT NULL | 필수 ↔ NOT NULL |
| product.reserved | products.reserved | number / long / BIGINT NOT NULL DEFAULT 0 | 요청에 없음(생성 시 0) |
| product.available | (컬럼 없음) = stock - reserved | number / long | 파생 |
| coupon.code | coupons.code | string / VARCHAR(20) UNIQUE NOT NULL | 필수 ↔ NOT NULL |
| coupon.type | coupons.type | `FIXED`/`RATE` / enum / VARCHAR(5) | 필수 ↔ NOT NULL |
| coupon.value | coupons.value | number / long / BIGINT NOT NULL | 필수 ↔ NOT NULL |
| coupon.minOrderAmount | coupons.min_order_amount | number / long / BIGINT NOT NULL DEFAULT 0 | 선택(생략=0) ↔ NOT NULL(앱이 0 채움) |
| coupon.maxDiscountAmount | coupons.max_discount_amount | number\|null / Long / BIGINT NULL | 선택 ↔ NULL 허용 |
| coupon.totalQuantity | coupons.total_quantity | number / long / BIGINT NOT NULL | 필수 ↔ NOT NULL |
| coupon.usedCount | coupons.used_count | number / long / BIGINT NOT NULL DEFAULT 0 (저장 컬럼) | 요청에 없음(0) |
| coupon.validFrom / validUntil | coupons.valid_from / valid_until | ISO 문자열 / Instant / TIMESTAMPTZ NOT NULL | 필수 ↔ NOT NULL |
| order.id | orders.id | number / long / BIGINT identity | 서버 생성 |
| order.userId | orders.user_id | string / VARCHAR(50) NOT NULL | 헤더 필수 ↔ NOT NULL |
| order.status | orders.status | enum 문자열 / VARCHAR(20) NOT NULL | 서버 |
| order.items[].productId | order_items.product_id | number / BIGINT NOT NULL FK | 필수 ↔ NOT NULL |
| order.items[].quantity | order_items.quantity | number / BIGINT NOT NULL | 필수 ↔ NOT NULL |
| order.items[].unitPrice | order_items.unit_price | number / BIGINT NOT NULL (주문 시점 가격 스냅샷) | 서버 |
| order.couponCode | orders.coupon_code | string\|null / VARCHAR(20) NULL | 선택 ↔ NULL 허용 |
| order.subtotal / discount / totalPrice | orders.subtotal / discount / total_price | number / long / BIGINT NOT NULL | 서버 계산 |
| order.createdAt / expiresAt | orders.created_at / expires_at | ISO 문자열(UTC) / Instant / TIMESTAMPTZ NOT NULL | 서버 |
| order.paidAt | orders.paid_at | ISO\|null / TIMESTAMPTZ NULL | 서버 |
| list.nextCursor | (컬럼 없음) = 마지막 원소의 (created_at 마이크로초, id) 인코딩 | string\|null | 파생 |
| (응답 비노출) | orders.coupon_id, orders.payment_id, orders.gateway_call_started_at, idempotency_keys.* | — | 내부 전용. 카드 토큰은 어디에도 저장 안 함 |

### B. 제약·상황 ↔ 상태코드

| 제약 / 상황 | 감지 방식 | 응답 |
|---|---|---|
| Bean Validation / 서비스 검증 / 파싱 실패 / 헤더·쿼리 오류 | 컨트롤러·서비스(DB 이전) | 400 VALIDATION_ERROR |
| `uq_coupons_code` 위반 | INSERT 시 제약 위반(제약명으로 판별) | 409 DUPLICATE_COUPON_CODE |
| `uq_idem_endpoint_key` 충돌 + 지문 불일치 | begin 시 조회 | 422 IDEMPOTENCY_KEY_MISMATCH |
| `uq_idem_endpoint_key` 충돌 + 지문 일치 + IN_PROGRESS(신선) | begin 시 조회 | 409 IDEMPOTENCY_IN_PROGRESS |
| `uq_idem_endpoint_key` 충돌 + 지문 일치 + COMPLETED | begin 시 조회 | 저장된 상태코드·본문 재생 |
| 상품 id 없음 | 존재 조회(주문 4단계) / GET | 404 PRODUCT_NOT_FOUND |
| 쿠폰 code 없음 | 존재 조회 | 404 COUPON_NOT_FOUND |
| 주문 id 없음 | 행 조회/락 | 404 ORDER_NOT_FOUND |
| 상품 조건부 UPDATE(`stock - reserved >= q`) 0행 | 영향 행 수 | 409 INSUFFICIENT_STOCK |
| 쿠폰 기간/최소금액/사용자 중복(사전 조회) | 쿠폰 행 락 후 판정 | 409 COUPON_NOT_APPLICABLE |
| `uq_orders_active_coupon_user` 위반(사전 조회를 통과한 경합의 최후 방어) | INSERT orders 시 제약 위반(제약명) | 409 COUPON_NOT_APPLICABLE |
| 쿠폰 조건부 UPDATE(`used_count < total_quantity`) 0행 | 영향 행 수 | 409 COUPON_EXHAUSTED |
| 주문 상태 전이 불가 / 만료 경과 결제 / 결제·환불 진행 중 | 주문 행 락 아래 상태·표지 확인 | 409 INVALID_STATE |
| PG DECLINED | PG 응답 | 402 PAYMENT_DECLINED |
| PG 5xx / 연결 실패 / 2초 초과 / 해석 불가 | 클라이언트 예외 | 503 PAYMENT_GATEWAY_UNAVAILABLE |
| CHECK 제약(`ck_*`), FK 위반, 그 밖의 DB 오류 | 앱 검증이 선행하므로 발생하면 버그 | 500 (code 생략) |
