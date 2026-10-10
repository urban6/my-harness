# 01. API 설계 — order-payment

- 단일 진실 원천: `/Users/jeonseungchul/SideProjects/my_harness/playground/exp4/runs/pilot-C/feature.md` (R1~R11, C1~C4, PG 계약)
- 스택: Java 21 / Spring Boot 3.5.16 / JPA(Hibernate) + PostgreSQL / Flyway / JUnit5 + Testcontainers. 기존 코드는 `OrderApplication` 외 없음(그린필드). 기준 패키지 `com.example.order`.
- DB 스키마·DDL·락 쿼리는 `02_db_design.md`. 이 문서 끝의 **정합 요약**이 구현·검증의 유일한 기준이다.
- 표기: `결정:` = 설계자가 확정한 사항(근거 한 줄 포함).

---

## 0. 공통 결정

### 0.1 금액·정수 (C1)
- 금액 필드 전부 Java `long` / `Long`(요청 DTO는 null 판별을 위해 래퍼), DB `BIGINT`, JSON 숫자(문자열 아님).
  대상: `price`, `unitPrice`, `subtotal`, `discount`, `totalPrice`, 쿠폰 `value`(FIXED는 금액), `minOrderAmount`, `maxDiscountAmount`, PG `amount`.
- 오버플로 방지: price ≤ 10,000,000 × quantity ≤ 1,000 × items ≤ 20 = 2.0e11, RATE 곱셈 subtotal(2.0e11) × value(≤100) = 2.0e13 → 모두 `long` 안. 계산은 `long`만 사용(`int` 캐스팅 금지). RATE 할인 = `subtotal * value / 100` (둘 다 양수라 정수 나눗셈 = floor).
- **int 초과 값을 오류 없이 받아야 하는 필드**(상한 검증 없음, 하한만): 쿠폰 `value`(FIXED, ≥1), `minOrderAmount`(≥0), `maxDiscountAmount`(≥1). `Long` 범위 초과(예 1e30)는 JSON 파싱 실패 → 400.
- **범위 검증으로 400 이어야 하는 필드**: 상품 `price` 1~10,000,000, `stock` 0~1,000,000, 주문 `quantity` 1~1,000, RATE `value` 1~100, `totalQuantity` ≥1 (`Integer`; int 초과는 파싱 실패 400), `size` 1~100.
- 응답의 `stock`/`reserved`/`available`/`quantity`/`usedCount`/`totalQuantity`는 `int`, 나머지 금액은 `long`.

### 0.2 시각 (C2)
- DB `TIMESTAMPTZ`, 엔티티 `java.time.Instant`, DTO `java.time.OffsetDateTime`. 응답은 항상 UTC 오프셋(`Z`)으로 정규화: `instant.atOffset(ZoneOffset.UTC)`. 직렬화 결과 예: `2026-10-10T01:02:03.123456Z`.
- 결정: 입력 오프셋을 보존하지 않고 UTC로 정규화해 돌려준다(근거: 동일 순간이면 충분, 오프셋 저장 컬럼 추가는 과설계).
- 입력(`validFrom`, `validUntil`)은 오프셋 포함 ISO-8601만 허용(`2030-01-01T00:00:00+09:00`, `...Z`). 오프셋 없는 `2030-01-01T00:00:00`, 날짜만, 숫자 타임스탬프는 400.
- 앱이 생성하는 시각(`createdAt`, `expiresAt`, `paidAt`)은 `Clock` 빈에서 `Instant.now(clock).truncatedTo(ChronoUnit.MICROS)`. (근거: PG의 마이크로초 정밀도와 일치시켜 응답값 = 저장값 = 커서값을 보장)
- Jackson 설정(application.yml): `spring.jackson.serialization.write-dates-as-timestamps: false`, `spring.jackson.deserialization.adjust-dates-to-context-time-zone: false`, `spring.jackson.time-zone: UTC`. Hibernate: `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`.

### 0.3 JSON 엄격성 (400 보장)
- `spring.jackson.deserialization.accept-float-as-int: false` (1.5 → int/long 필드 조용한 절단 금지, 400)
- `spring.jackson.mapper.allow-coercion-of-scalars: false` (`"10"` → 숫자, `123` → String 암묵 변환 금지, 400)
- enum 대소문자 구분(`fixed` → 400). 알 수 없는 속성은 무시(Boot 기본). `null` 필수 필드는 검증 실패.
- 응답은 null 필드를 생략하지 않는다(`couponCode`, `paidAt`, `maxDiscountAmount`, `nextCursor`는 `null` 로 출력). `spring.jackson.default-property-inclusion` 기본(ALWAYS) 유지.
- 결제 게이트웨이 응답 파싱은 앱 `ObjectMapper`가 아닌 `JsonNode`로 느슨하게 읽는다(`paymentId`는 `asText()`).

### 0.4 ID·Location
- 모든 id는 `BIGSERIAL`/`Long`, JSON 숫자. 경로 변수 `{id}`가 Long으로 변환 불가(`abc`) → 400 `VALIDATION_ERROR`(결정: R11 "변환 실패는 400"에 일관). 변환은 되나 없으면 404.
- `Location`은 절대 URL: `ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/orders/{id}").buildAndExpand(id)`. 멱등 재생 시에도 저장된 `response_location`(경로만 저장)을 현재 요청 컨텍스트로 절대화.

### 0.5 에러 포맷 (R11)
- RFC 9457. `Content-Type: application/problem+json` — **`ResponseEntity.contentType(MediaType.APPLICATION_PROBLEM_JSON)`를 명시**(Accept: application/json 요청에도 406이 나지 않게 협상을 우회).
- 본문 필드: `type`(URI), `title`, `status`(int), `detail`(사람이 읽는 설명), `code`(확장 멤버). 검증 오류는 추가로 `errors: [{field, message}]`(선택, 테스트는 의존하지 않음).
- `type` = `https://example.com/problems/{code를 kebab-case로}` (예: `.../validation-error`). `title`은 아래 표.
- 구현: `@RestControllerAdvice extends ResponseEntityExceptionHandler`, `spring.mvc.problemdetails.enabled: true`. 도메인 예외 `ApiException(HttpStatus, ErrorCode, detail)` 하나로 통일.

| status | code | title | 발생 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Validation failed | 본문/헤더/쿼리/경로 검증·변환 실패, JSON 파싱 실패, 본문 누락 |
| 402 | `PAYMENT_DECLINED` | Payment declined | PG DECLINED |
| 404 | `PRODUCT_NOT_FOUND` | Product not found | 주문 항목 상품 없음, `GET /api/products/{id}` |
| 404 | `COUPON_NOT_FOUND` | Coupon not found | 주문 couponCode 없음, `GET /api/coupons/{code}` |
| 404 | `ORDER_NOT_FOUND` | Order not found | 주문 조회/결제/취소/배송 대상 없음 |
| 409 | `INSUFFICIENT_STOCK` | Insufficient stock | available < quantity |
| 409 | `COUPON_NOT_APPLICABLE` | Coupon not applicable | 기간·최소금액·동일 사용자 사용 중 |
| 409 | `COUPON_EXHAUSTED` | Coupon exhausted | usedCount = totalQuantity |
| 409 | `DUPLICATE_COUPON_CODE` | Duplicate coupon code | 쿠폰 code 중복 |
| 409 | `INVALID_STATE` | Invalid order state | 상태 전이 불가/만료/PG 호출 진행 중 |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Idempotent request in progress | 같은 키 처리 중 |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | Idempotency key mismatch | 같은 키, 다른 요청 |
| 503 | `PAYMENT_GATEWAY_UNAVAILABLE` | Payment gateway unavailable | PG 장애 |

- 범위 밖 오류(405/404 라우트/415/406)는 `ResponseEntityExceptionHandler` 기본 ProblemDetail 유지 + `code`는 `METHOD_NOT_ALLOWED`/`NOT_FOUND`/`UNSUPPORTED_MEDIA_TYPE`/`NOT_ACCEPTABLE`. 예기치 못한 예외는 500 `INTERNAL_ERROR`(스택 노출 금지). 위 표의 코드만 R11 계약.
- 400으로 매핑할 예외(전부 `VALIDATION_ERROR`): `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `HttpMessageNotReadableException`(파싱 실패·타입 불일치·본문 누락·날짜 형식·enum 불일치), `MissingRequestHeaderException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`/`TypeMismatchException`, `MissingPathVariableException`.

### 0.6 주문 응답 형태 (R3.5) — `OrderResponse`
주문 생성(201)·조회·결제(200)·취소·배송·완료·목록 `content[]` 모두 동일.
```json
{
  "id": 7, "userId": "u1", "status": "PENDING_PAYMENT",
  "items": [{"productId": 3, "quantity": 2, "unitPrice": 15000}],
  "couponCode": "SAVE10",
  "subtotal": 30000, "discount": 3000, "totalPrice": 27000,
  "createdAt": "2026-10-10T01:02:03.123456Z",
  "expiresAt": "2026-10-10T01:17:03.123456Z",
  "paidAt": null
}
```
`items`는 요청 순서(`line_no`) 유지. `couponCode`/`paidAt` 없으면 `null`. `paymentId`, `gatewayInflightSince`는 응답에 **노출하지 않는다**.

### 0.7 패키지 제안
`com.example.order.{product,coupon,order,idempotency,payment,common}`; common에 `ApiException`, `ErrorCode`, `GlobalExceptionHandler`, `ClockConfig`, `JacksonConfig`(필요 시). 서비스 간 self-invocation으로 `@Transactional`이 무시되지 않도록 트랜잭션 경계는 별도 빈에 둔다.

---

## 1. 엔드포인트

### 1.1 `POST /api/products` (R1.1~R1.2)
- 요청: `{ "name": string, "price": long, "stock": int }`
- 검증(위반 400): `name` 필수·`isBlank()` 불가·길이 ≤100(저장 시 trim하지 않고 원문 보존), `price` 1~10,000,000, `stock` 0~1,000,000. 전부 `Long`/`Integer` 래퍼 + `@NotNull`.
- 201 + `Location: /api/products/{id}`, 본문 `ProductResponse`(`reserved`=0).
- `ProductResponse`: `{id, name, price, stock, reserved, available}` (`available = stock - reserved`, 서버 계산).

### 1.2 `GET /api/products/{id}` (R1.3)
- 200 `ProductResponse`. 없으면 404 `PRODUCT_NOT_FOUND`. (만료 반영은 스케줄러, §4.)

### 1.3 `POST /api/coupons` (R2.1~R2.2)
- 요청: `{code, type, value, minOrderAmount, maxDiscountAmount, totalQuantity, validFrom, validUntil}`
- 검증(400):
  - `code`: 필수, 정규식 `^[A-Z0-9]{4,20}$`
  - `type`: 필수, `FIXED`|`RATE`
  - `value`: 필수 `Long`. FIXED → ≥1(상한 없음). RATE → 1~100. (type 의존 검증은 `@AssertTrue` 또는 서비스 진입 전 수동 검증; type이 null/invalid면 type 오류로 이미 400)
  - `minOrderAmount`: 생략/`null` → 0, 명시값은 ≥0
  - `maxDiscountAmount`: 생략/`null` → 제한 없음(DB NULL), 명시값은 ≥1 (0 또는 음수 → 400)
  - `totalQuantity`: 필수, ≥1
  - `validFrom`, `validUntil`: 필수, 오프셋 포함 ISO-8601, `validFrom < validUntil` (같거나 역전 → 400)
- 검증 통과 후 `code` 중복 → 409 `DUPLICATE_COUPON_CODE`. 구현: `existsByCode` 사전 확인 + 동시 삽입 대비 `saveAndFlush`에서 유니크 위반(`uk_coupons_code`) catch → 동일 409.
- 201 + `Location: /api/coupons/{code}`, 본문 `CouponResponse`(`usedCount`=0).
- `CouponResponse`: `{code, type, value, minOrderAmount, maxDiscountAmount|null, totalQuantity, usedCount, validFrom, validUntil}` (내부 id 미노출).
- 결정: 쿠폰 `Location`의 경로 변수는 `code`(GET이 code 기반이므로).

### 1.4 `GET /api/coupons/{code}` (R2.3)
- 200 `CouponResponse`. 없으면 404 `COUPON_NOT_FOUND`. 경로의 code는 형식 검증하지 않는다(없으면 404).

### 1.5 `POST /api/orders` (R3, R4, R2.4~R2.6)
- 헤더: `X-User-Id`(필수, 공백만 불가, 길이 1~50), `Idempotency-Key`(필수, 길이 1~64, 공백만 불가).
- 요청: `{ "items": [{"productId": long, "quantity": int}], "couponCode": string|null }`
- 검증(400): `items` 필수·1~20개·원소 null 불가, `productId` 필수, `quantity` 필수 1~1,000, 같은 `productId` 중복 불가, `couponCode`는 생략/`null` 허용, 문자열이면 비어 있거나 공백만이면 400(결정: 빈 문자열은 의도 불명이므로 400). 코드 형식(정규식)은 검증하지 않고 조회 결과로 404(결정: 존재하지 않는 코드는 형식과 무관하게 404가 R3.3에 부합).
- 처리: §3.1 파이프라인. 성공 201 + `Location: /api/orders/{id}` + `OrderResponse`(`status`=`PENDING_PAYMENT`, `paidAt`=null).
- 오류: 400, 422 `IDEMPOTENCY_KEY_MISMATCH`, 409 `IDEMPOTENCY_IN_PROGRESS`, 404 `PRODUCT_NOT_FOUND`/`COUPON_NOT_FOUND`, 409 `INSUFFICIENT_STOCK` → `COUPON_NOT_APPLICABLE` → `COUPON_EXHAUSTED`.
- 할인 계산(R2.4): `subtotal = Σ(unitPrice×quantity)`; FIXED → `value`, RATE → `subtotal*value/100`; `maxDiscountAmount != null`이면 `min(discount, max)`; 마지막 `min(discount, subtotal)`; `totalPrice = subtotal - discount`. `unitPrice` = 주문 시점 `products.price` 스냅샷. 쿠폰 없으면 `discount=0`.
- 쿠폰 적용 조건 판정 순서(결정: R2.5 기재 순서): ①기간 `validFrom ≤ now < validUntil` ②`subtotal ≥ minOrderAmount` ③동일 사용자의 "사용 중" 주문 없음 → 하나라도 위반이면 `COUPON_NOT_APPLICABLE`; 통과 후 ④`usedCount ≥ totalQuantity` → `COUPON_EXHAUSTED`. `now`는 상품·쿠폰 락 획득 직후 한 번 읽은 `Clock` 시각.
- "사용 중" 주문 상태 정의: `PENDING_PAYMENT`, `PAID`, `SHIPPED`, `DELIVERED`. (`PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`는 사용 중이 아님.)

### 1.6 `GET /api/orders/{id}` (R3.5, R6)
- 200 `OrderResponse`. 없으면 404 `ORDER_NOT_FOUND`.
- 지연 평가: 조회한 주문이 `PENDING_PAYMENT`이고 `expiresAt ≤ now`이며 PG 호출 진행 중이 아니면, 만료 처리(§4.2 `expireOne`)를 커밋한 뒤 최신 상태로 응답.

### 1.7 `GET /api/orders` (R9)
- 쿼리: `userId`(선택; 주어졌는데 공백만이면 400), `status`(선택; 정의된 8개 enum 외 → 400, 대소문자 구분, 빈 값 400), `size`(기본 20, 1~100, 정수 아님/범위 밖 400), `cursor`(선택, 해석 불가 → 400; 빈 문자열도 400).
- 200 `{ "content": [OrderResponse...], "nextCursor": string|null }`
- 정렬: `created_at DESC, id DESC`. keyset 페이지네이션 (§5). 다음 페이지가 없으면 `nextCursor=null`.
- 필터 둘 다 주면 AND. 만료 지연 평가는 하지 않는다(스케줄러 의존, §4).

### 1.8 `POST /api/orders/{id}/pay` (R5, R4)
- 헤더: `Idempotency-Key`(필수 1~64). `X-User-Id`는 선택 — 주면 1~50 공백불가 검증(위반 400), 멱등 지문에 포함, 소유자 검증은 하지 않는다(인증 범위 밖).
- 요청: `{ "cardToken": string }` — 필수, `isBlank()` 불가(앞뒤 공백 포함 원문을 PG로 그대로 전달, trim 금지). 길이 상한은 두지 않는다.
- 처리: §3.2 파이프라인.
- 응답: 200 `OrderResponse`(`status`=`PAID`, `paidAt` 기록) / 402 `PAYMENT_DECLINED` / 503 `PAYMENT_GATEWAY_UNAVAILABLE` / 404 / 409 `INVALID_STATE` / 400 / 422 / 409 `IDEMPOTENCY_IN_PROGRESS`.

### 1.9 `POST /api/orders/{id}/cancel` (R7)
- 헤더/본문 요구 없음(본문은 읽지 않음). 멱등 키 대상 아님.
- 200 `OrderResponse`: `PENDING_PAYMENT` → `CANCELLED`(복원), `PAID` → PG 환불 성공 시 `REFUNDED`. 404 / 409 `INVALID_STATE`(그 외 상태, 만료됨, PG 호출 진행 중) / 503 `PAYMENT_GATEWAY_UNAVAILABLE`(환불 PG 장애, 상태 불변).

### 1.10 `POST /api/orders/{id}/ship`, `POST /api/orders/{id}/deliver` (R8)
- 200 `OrderResponse`. ship: `PAID`→`SHIPPED`, deliver: `SHIPPED`→`DELIVERED`. 그 외 상태 409 `INVALID_STATE`, 없으면 404. 멱등 키 불필요.

---

## 2. 전이별 수량 변화표

`q` = 주문 항목의 상품별 수량. "쿠폰"은 주문에 쿠폰이 있을 때만 해당.

| 전이 | 트리거 | product.stock | product.reserved | coupon.usedCount | "사용 중" 인덱스 |
|---|---|---|---|---|---|
| (생성) → PENDING_PAYMENT | POST /orders | 불변 | +q | +1 | 점유 |
| PENDING_PAYMENT → PAID | 결제 승인 / total=0 | −q | −q | 불변 | 유지 |
| PENDING_PAYMENT → PAYMENT_FAILED | PG DECLINED | 불변 | −q | −1 | 해제 |
| PENDING_PAYMENT → EXPIRED | `expiresAt` 경과 | 불변 | −q | −1 | 해제 |
| PENDING_PAYMENT → CANCELLED | 취소 | 불변 | −q | −1 | 해제 |
| PAID → REFUNDED | 취소(PG 환불 성공 / paymentId 없음) | +q | **불변** | −1 | 해제 |
| PAID → SHIPPED | ship | 불변 | 불변 | 불변 | 유지 |
| SHIPPED → DELIVERED | deliver | 불변 | 불변 | 불변 | 유지 |
| 그 밖 | — | 409 INVALID_STATE (변화 없음) | | | |

- DELIVERED는 환불 불가(R7.4)이므로 쿠폰은 복원되지 않는다.
- 불변식: `0 ≤ reserved ≤ stock`, `0 ≤ usedCount ≤ totalQuantity`(DB CHECK로도 방어, `02_db_design.md`).
- 상태 변경 UPDATE는 항상 **주문 행 `SELECT ... FOR UPDATE` 후 현재 status 재확인**(상태 조건부 전이)로만 수행한다. 만료·결제·취소·배송이 동시에 와도 하나만 성공.

---

## 3. 처리 파이프라인 (C3, R4)

공통 원칙: 오류 우선순위 **400 → 멱등키(422·409) → 404 → 409(재고 → 쿠폰) → PG(402·503)**. 단계 앞에서 걸리면 뒤 단계는 실행하지 않는다(DB 락도 잡지 않는다).

### 3.0 멱등성 공통 (R4)
- 키 공간: `(scope, Idempotency-Key)` 유니크. `scope` ∈ {`ORDER_CREATE`, `ORDER_PAY`} → 두 엔드포인트 독립. 사용자는 키 공간에 포함되지 **않는다**(다른 `X-User-Id`로 같은 키 → 다른 요청 → 422).
- 요청 동일성(지문) = `(X-User-Id, 요청 경로, 정규화 본문)`.
  - ORDER_CREATE: 경로 `/api/orders`, 본문 정규형 `items=[productId:quantity,...(요청 순서)];coupon=<couponCode|∅>`
  - ORDER_PAY: 경로 `/api/orders/{orderId}/pay`(orderId는 파싱된 long으로 정규화), 본문 정규형 `cardToken=<원문>`; X-User-Id 없으면 `∅`.
  - 정규화는 **원문 JSON이 아니라 검증을 통과한 DTO**에서 만든다 → 공백·키 순서·`couponCode:null` vs 생략·알 수 없는 속성 차이는 같은 요청으로 취급. 배열 `items` 순서는 의미 있는 값으로 취급(순서가 다르면 다른 요청).
  - 저장: `user_id`, `request_path`, `body_hash`(정규 본문의 SHA-256 hex) 컬럼을 각각 비교. 하나라도 다르면 mismatch.
- 알고리즘 (`IdempotencyService`, **트랜잭션 비포함 파사드에서 호출**, 중첩 커넥션 점유 금지):
  1. `claim`(새 짧은 트랜잭션, 즉시 커밋): `INSERT ... ON CONFLICT (scope, idem_key) DO NOTHING`. 삽입 1행 → `CLAIMED`.
  2. 삽입 0행 → 기존 행 `SELECT`(락 없음). 행이 사라졌으면(소유자가 해제함) 1로 재시도(최대 3회). 있으면: 지문 불일치 → **422** `IDEMPOTENCY_KEY_MISMATCH`(상태 무관 우선) → `COMPLETED` → **재생**(저장된 상태코드·본문·Location 그대로) → `IN_PROGRESS` → **409** `IDEMPOTENCY_IN_PROGRESS`.
  3. `CLAIMED`면 비즈니스 처리. **2xx 성공**: 같은 비즈니스 트랜잭션 안에서 `complete`(`Propagation.MANDATORY`)로 `status=COMPLETED, response_status, response_body(직렬화된 JSON 문자열), response_location` 기록 → 업무 변경과 완료 기록이 원자적. **그 외 어떤 오류(4xx/5xx/예외)**: 새 트랜잭션으로 해당 claim 행 삭제(`DELETE ... WHERE id=? AND status='IN_PROGRESS'`) = 키 해제 → 같은 요청 재시도 가능(R4.4).
  4. 동시 동일 키 N건: 유니크 제약 때문에 정확히 한 건만 CLAIMED, 나머지는 2번 경로(재생 또는 409). 실제 처리는 1회(R4.5).
- 응답 본문 재생은 최초 응답 JSON 문자열을 그대로 반환(`Content-Type: application/json`). 예: PAID 이후 `shipped`가 되었어도 재생은 최초 본문.
- 범위 밖: 멱등 키 만료, 프로세스 크래시로 남은 `IN_PROGRESS` 정리.

### 3.1 `POST /api/orders` 파이프라인
```
[1] 400  헤더(X-User-Id, Idempotency-Key) 누락·형식, 본문 파싱/타입, items/quantity/중복/couponCode 검증   (락·DB 접근 없음)
[2] 멱등키  claim → 422 MISMATCH / 409 IN_PROGRESS / 재생(201)
    ── 이하 하나의 @Transactional (OrderCreationService.create), READ COMMITTED ──
[3] 상품 행 잠금: SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE   (id 오름차순; 다중 상품 데드락 방지)
      · 조회 행 수 < 요청 상품 수 → 404 PRODUCT_NOT_FOUND (요청 순서상 처음 없는 id)
[4] couponCode 있으면 쿠폰 행 SELECT ... FOR UPDATE (code 기준)  → 없으면 404 COUPON_NOT_FOUND
      · now = Clock (마이크로초 절단)  — createdAt도 이 시각 (락 획득 후 확정; 커밋 순서와 createdAt 순서 불일치 최소화)
[5] 409 INSUFFICIENT_STOCK : 요청 순서대로 (stock - reserved) < quantity 인 첫 항목
[6] subtotal 계산 → 쿠폰: 409 COUPON_NOT_APPLICABLE(기간→최소금액→동일 사용자 사용 중 조회) → 409 COUPON_EXHAUSTED
[7] 적용: product.reserved += q ; coupon.used_count += 1 ; discount/total 계산 ; orders + order_items INSERT(flush)
      · uk_orders_user_coupon_in_use 위반(동시 삽입 방어선) → 409 COUPON_NOT_APPLICABLE (트랜잭션 롤백)
[8] OrderResponse 직렬화 → complete(idempotency) → 201 + Location
    (어느 단계든 예외 → 전체 롤백 = 예약·쿠폰 사용 전무) → 파사드가 claim 해제
```
- 락 순서 전역 규칙: **주문 행 → 상품 행(id 오름차순) → 쿠폰 행**. 생성은 주문 행이 아직 없으므로 상품 → 쿠폰. 결제/취소/만료 트랜잭션도 동일 순서 → 사이클 없음(R10.4).
- 결정: 재고 경합은 `FOR UPDATE` 락 + 락 후 available 검사(조건부 UPDATE 대신). 근거: 404 → 409(재고) → 409(쿠폰) 순서를 정확히 보고하려면 검사와 갱신을 분리해야 하며, 락이 쿠폰까지 이어져 R10.1~R10.3을 단순하게 직렬화한다.
- R10.2: 쿠폰 행 락으로 직렬화되어 정확히 5건 통과 나머지 `COUPON_EXHAUSTED`. R10.3: 쿠폰 락 → 먼저 커밋된 주문이 "사용 중"이므로 나머지는 `COUPON_NOT_APPLICABLE`; 락을 우회하는 경로가 있어도 부분 유니크 인덱스가 최종 보증.
- 락 대기가 길어도 오류로 바꾸지 않는다(`lock_timeout` 미설정; 기본 대기).

### 3.2 `POST /api/orders/{id}/pay` 파이프라인 (결정: PG 호출은 트랜잭션 밖, 진행 표식 방식)
근거: PG 호출(최대 2초) 동안 DB 커넥션·주문 행 락을 잡고 있으면 동시 결제 요청 N개가 풀을 모두 점유한다. 표식(`gateway_inflight_since`)을 커밋해 선점하고 PG는 트랜잭션 밖에서 호출하면 같은 주문의 PG 호출이 정확히 1건으로 제한된다(R10.5).
```
[1] 400  Idempotency-Key 누락/형식, (X-User-Id 주어졌으면 형식), {id} 변환, cardToken 누락/공백/타입, 본문 파싱
[2] 멱등키 claim (scope ORDER_PAY, 지문에 orderId 경로 포함) → 422 / 409 IN_PROGRESS / 재생(200)
[3] Tx1 (짧음, PayPrecheckService.begin):
      a. SELECT orders WHERE id FOR UPDATE → 없으면 404 ORDER_NOT_FOUND
      b. 지연 만료: status=PENDING_PAYMENT AND expires_at <= now AND 진행 표식 없음 → expireOne 로직을 이 트랜잭션에서 수행·커밋 후 409 INVALID_STATE 반환
         (예외로 던지면 롤백되므로 Tx1은 결과 객체 Reject(INVALID_STATE)를 반환하고 커밋한다)
      c. status != PENDING_PAYMENT, 또는 진행 표식 유효(gateway_inflight_since > now-10s) → 409 INVALID_STATE
      d. total_price == 0 → PG 미호출: 같은 Tx1에서 바로 PAID 전이(§2 PAID 행), payment_id=NULL, paid_at=now, complete(idempotency) → 200
      e. 그 외: gateway_inflight_since = now 기록 → 커밋. (orderId, totalPrice) 스냅샷을 반환
[4] PG 결제 호출 (트랜잭션 밖, 커넥션 미점유): POST {PAYMENT_GATEWAY_URL}/v1/payments
      헤더 Idempotency-Key = 클라이언트 키 그대로, 본문 {orderId(number), amount(totalPrice, number), cardToken(원문)}
[5] Tx2 (PayResultService.finish): 주문 행 FOR UPDATE → 표식 해제(NULL) → 결과 반영
      · APPROVED : 상품 행 id 오름차순 잠금 후 stock−=q, reserved−=q ; status=PAID ; paid_at=now ; payment_id=paymentId ; complete(idempotency) → 200 OrderResponse
      · DECLINED : 상품 잠금 → reserved−=q ; 쿠폰 잠금 → used_count−=1 ; status=PAYMENT_FAILED → 402 PAYMENT_DECLINED (키 해제)
      · UNAVAILABLE: 표식만 해제, 주문·재고·쿠폰 불변 → 503 PAYMENT_GATEWAY_UNAVAILABLE (키 해제)
```
- `finally` 성격 보장: [4]에서 어떤 예외가 나도 Tx2(표식 해제)는 반드시 실행. 해제하지 못한 경우를 위해 표식은 10초 지나면 무효(크래시 방어, 상수 `GATEWAY_INFLIGHT_TTL=10s`).
- 동시 결제(R10.5): 같은 주문에 키가 다른 결제 N건 → Tx1의 주문 행 락으로 직렬화, 1건만 표식을 선점해 PG 호출, 나머지는 Tx1c에서 `409 INVALID_STATE`(진행 중 또는 이미 PAID). PG 결제 요청 최대 1번, 성공 응답 1건. 선점자가 503이면 표식이 풀리므로 이후 도착한 요청은 정상 재시도 가능.
- PG 승인 후 Tx2 DB 실패 같은 극단 상황: 표식 만료 후 같은 `Idempotency-Key`로 재시도하면 PG가 최초 결과(APPROVED)를 돌려주므로 복구 가능(클라이언트 키를 PG 키로 그대로 쓰는 이유).
- 표식이 유효한 동안 만료 스케줄러·지연 평가·취소·배송은 해당 주문을 건드리지 않는다(PG가 승인하는 중 EXPIRED/CANCELLED로 뒤집히는 이중 처리 방지). 결과적으로 결제 진행 중 `expiresAt`이 지나도 결제 결과(승인 시 PAID, 거절 시 PAYMENT_FAILED)가 우선한다.
- Tx2는 상태가 `PENDING_PAYMENT`임을 재확인한다(표식이 지켜지므로 항상 성립; 어긋나면 500으로 취급하고 로그).

### 3.3 `POST /api/orders/{id}/cancel` 파이프라인
```
[1] (검증할 입력 없음; {id} 변환 실패만 400)
[2] Tx1: 주문 FOR UPDATE → 404 ; 지연 만료(위 3.2-b와 동일, 커밋 후 409 INVALID_STATE) ; 진행 표식 유효 → 409 INVALID_STATE
      · PENDING_PAYMENT → 상품 잠금 reserved−=q, 쿠폰 잠금 used_count−=1, status=CANCELLED → 200
      · PAID & payment_id IS NULL(0원 주문) → PG 미호출: stock+=q, 쿠폰 복원, status=REFUNDED → 200
      · PAID & payment_id 있음 → 표식 기록·커밋 → [3]
      · 그 외 → 409 INVALID_STATE
[3] PG 환불 호출 (트랜잭션 밖): POST {PAYMENT_GATEWAY_URL}/v1/payments/{paymentId}/refund (본문 없음)
[4] Tx2: 주문 FOR UPDATE → 표식 해제 →
      · 성공(200, status=="REFUNDED") : 상품 잠금 stock+=q (reserved 불변) ; 쿠폰 잠금 used_count−=1 ; status=REFUNDED → 200 OrderResponse
      · 장애/계약 외 응답 : 불변 → 503 PAYMENT_GATEWAY_UNAVAILABLE
```
- 결정: 0원 주문(payment_id 없음)의 환불은 PG를 호출하지 않는다(근거: PG에 결제 기록이 없어 환불 대상 paymentId가 존재하지 않음; R5.7과 대칭).

### 3.4 `ship` / `deliver`
`주문 FOR UPDATE → 404 → 진행 표식 유효 시 409 INVALID_STATE → 상태 검사(PAID→SHIPPED / SHIPPED→DELIVERED, 그 외 409) → 200`. 재고·쿠폰 변화 없음.

### 3.5 PG 클라이언트 (`PaymentGatewayClient`)
- `java.net.http.HttpClient`(버전 `HTTP_1_1` 고정: h2c 업그레이드가 스텁 서버에서 문제 일으키는 것 방지), `connectTimeout(2s)`.
- **전체 데드라인 2초**: `sendAsync(request, BodyHandlers.ofString()).get(2000, ms)` + `HttpRequest.timeout(2s)`. 데드라인 초과 시 future cancel. (연결+읽기 합산 2초 이내 보장)
- 설정: `payment.gateway.url`(env `PAYMENT_GATEWAY_URL`), `payment.gateway.timeout`(기본 `PT2S`, env 아님).
- 결과 분류(`PgResult`):
  - 200 + `status=="APPROVED"` → `APPROVED(paymentId)` ; 200 + `status=="DECLINED"` → `DECLINED`
  - 5xx, 연결 실패, 타임아웃, `IOException`, 인터럽트 → `UNAVAILABLE`
  - **결정: PG 4xx, 200이나 알 수 없는 status, JSON 파싱 불가, paymentId 누락 → 계약 외 응답이므로 `UNAVAILABLE`(503, 상태 불변)로 취급**(근거: 결제 결과를 단정할 수 없을 때 돈·재고 상태를 바꾸지 않는 것이 안전하고, 같은 키로 재시도 가능).
  - 환불: 200 + `status=="REFUNDED"` → 성공, 그 외 모두 `UNAVAILABLE`.
- `cardToken`은 로그에 남기지 않는다. 요청 헤더 `Content-Type: application/json`, `Accept: application/json`.
- PG 결제 본문의 `orderId`는 응답의 `id`와 같은 숫자 표현(JSON number)이다.

---

## 4. 결제 만료 (R6)

### 4.1 `expiresAt`
`expiresAt = createdAt + ORDER_PAYMENT_TTL`(Duration, 기본 `PT15M`). 생성 시 계산해 `orders.expires_at`에 저장(이후 설정이 바뀌어도 기존 주문 불변). 설정 빈에서 매 주문 생성마다 현재 값을 읽는다(정적 캐시 금지).

### 4.2 방식: 주기 스케줄러 + 단건 지연 평가 병행
- `@EnableScheduling`, `ExpirationScheduler`: `@Scheduled(fixedDelayString = "${order.expiry.scan-interval:PT0.5S}")` (500ms 고정 지연, 겹침 없음). 만료 후 최대 ≈0.5s + 처리시간 안에 EXPIRED → R6.2의 2초 충족.
  1. 대상 id 조회(락 없음): `status='PENDING_PAYMENT' AND expires_at <= now AND (gateway_inflight_since IS NULL OR gateway_inflight_since < now-10s) ORDER BY expires_at LIMIT 100` (부분 인덱스 `idx_orders_pending_expiry`). 비어 있지 않으면 한 번 더 반복(최대 한 틱에 10회).
  2. id마다 별도 트랜잭션 `expireOne(id)`: 주문 `FOR UPDATE` → 상태·`expires_at`·표식 **재확인**(이미 PAID/CANCELLED면 no-op) → 상품 id 오름차순 잠금 `reserved−=q` → 쿠폰 잠금 `used_count−=1` → `status=EXPIRED`. 한 주문의 실패가 다른 주문을 막지 않게 catch·로그 후 계속.
- 지연 평가(스케줄러 지연과 무관하게 정확성 보장): `GET /api/orders/{id}`, `pay`, `cancel`의 Tx1이 같은 판정(`PENDING_PAYMENT AND expires_at <= now AND 표식 없음`)을 하고 `expireOne`을 수행. 따라서 R5.2 "expiresAt이 지났으면 409 INVALID_STATE"는 스케줄러 지연과 무관하다.
- 목록·상품·쿠폰 조회는 스케줄러에 의존(최대 지연 ≈0.5s). 결정 근거: 읽기 경로에 쓰기를 퍼뜨리지 않으면서 R6.2(2초)를 만족.
- 만료 판정 경계: `expires_at <= now` 이면 만료(스케줄러·지연 평가·결제 검사 모두 동일).
- 경합: 만료 ↔ 결제/취소 모두 주문 행 락 + 상태 조건부 전이로 직렬화, 승자 하나. 결제 진행 표식이 유효하면 만료하지 않음(§3.2).

---

## 5. 주문 목록 keyset 페이지네이션 (R9)

- 정렬 키 `(created_at DESC, id DESC)`. 다음 페이지 조건: `(created_at, id) < (cursor.createdAt, cursor.id)` (행 비교 또는 `created_at < :c OR (created_at = :c AND id < :id)`). `LIMIT size+1`로 가져와 초과 1건이 있으면 `nextCursor` 생성, 없으면 `null`.
- 커서 = 마지막 반환 원소의 `(createdAt, id)`. 포맷(불투명): `base64url(무패딩)( "v1|" + createdAtEpochMicros + "|" + id )`. 디코드 실패, 접두 불일치, 숫자 파싱 실패, 음수 id → 400 `VALIDATION_ERROR`. 길이 > 200 → 400.
- `createdAtEpochMicros = epochSecond*1_000_000 + nano/1000` (앱에서 이미 마이크로초 절단되어 DB 값과 정확히 같음).
- **스냅샷 상한을 커서에 넣지 않기로 결정**. 검토 결과:
  - 첫 페이지 시점에 이미 커밋된 주문은 `(created_at, id)`가 불변이므로, 이후 모든 페이지에서 키 순서대로 정확히 한 번씩 나온다(중복·누락 없음). 새 주문은 최신(커서보다 앞) 위치에 생기므로 이어지는 페이지에 끼어들지 않는다.
  - 커밋 순서와 `createdAt` 순서 불일치로 문제가 되는 것은 "첫 페이지 시점에 아직 커밋되지 않은" 주문뿐이며, R9.5는 이들을 보장 대상에서 제외한다. 이런 주문이 이후 페이지에 나타나는 것은 허용(중복 아님).
  - 불일치 자체를 줄이기 위해 `createdAt`을 상품·쿠폰 락 획득 직후(INSERT 직전)에 확정한다(§3.1[4]).
  - 상한 스냅샷은 커밋 지연 주문을 막지 못하면서 커서 복잡도만 늘리므로 채택하지 않음.
- 주의: 필터 `status`로 보는 동안 주문 상태가 바뀌면 해당 주문이 필터 결과에서 빠질 수 있다(R9.5 범위 밖).
- 인덱스: `02_db_design.md`의 `idx_orders_created_id`, `idx_orders_user_created`, `idx_orders_status_created`. 항목(`order_items`)은 페이지 단위 `IN` 배치 조회(`@BatchSize` 또는 명시 쿼리)로 N+1 방지.
- 구현 주의: 선택 필터는 `:p IS NULL OR col = :p` 패턴(PG에서 null 바인딩 타입 추론 오류 위험) 대신 조건부 조합(Criteria/`Specification` 또는 문자열 조립)으로 작성.

---

## 6. 검증 요약 (400 대상)

| 대상 | 규칙 |
|---|---|
| 상품 name | 필수, 공백만 불가, ≤100 |
| 상품 price / stock | 1~10,000,000 / 0~1,000,000 |
| 쿠폰 code | `^[A-Z0-9]{4,20}$` |
| 쿠폰 type | `FIXED`\|`RATE` |
| 쿠폰 value | FIXED ≥1 (상한 없음, long) / RATE 1~100 |
| 쿠폰 minOrderAmount | 생략=0, ≥0 (long) |
| 쿠폰 maxDiscountAmount | 생략=무제한, 명시 시 ≥1 (long) |
| 쿠폰 totalQuantity | ≥1 |
| 쿠폰 validFrom/Until | 오프셋 포함 ISO-8601, from < until |
| X-User-Id | 공백 아닌 1~50자 (주문 생성 필수, 결제 선택) |
| Idempotency-Key | 1~64자, 공백만 불가 (생성·결제 필수) |
| items | 1~20개, productId 필수·중복 불가, quantity 1~1,000 |
| couponCode(주문) | 생략/null 허용, 문자열이면 공백만 불가 |
| cardToken | 필수, 공백만 불가 |
| 목록 | status enum 8종, size 1~100(정수), cursor 해석 가능, userId 공백만 불가 |
| 공통 | JSON 파싱 실패, 타입 불일치(문자열↔숫자, 소수→정수), 본문 누락, 경로 id 변환 실패 |

---

## 7. 런타임 설정 (C4)

`src/main/resources/application.yml` 초안:
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/orders      # env SPRING_DATASOURCE_URL 이 relaxed binding 으로 덮어씀
    username: orders                                  # env SPRING_DATASOURCE_USERNAME
    password: orders                                  # env SPRING_DATASOURCE_PASSWORD
    hikari:
      maximum-pool-size: 20                           # R10 동시 20건 대응
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    time-zone: UTC
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      adjust-dates-to-context-time-zone: false
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false
  mvc:
    problemdetails:
      enabled: true

server:
  port: 8080                                          # env SERVER_PORT (relaxed binding)

payment:
  gateway:
    url: ${PAYMENT_GATEWAY_URL:http://localhost:9090}
    timeout: PT2S

order:
  payment:
    ttl: ${ORDER_PAYMENT_TTL:PT15M}
  expiry:
    scan-interval: PT0.5S
```
- `SPRING_DATASOURCE_*`, `SERVER_PORT`는 Spring Boot 환경변수 relaxed binding으로 자동 적용(별도 `${}` 불필요). `PAYMENT_GATEWAY_URL`, `ORDER_PAYMENT_TTL`은 사용자 정의 키라 명시적 `${ENV:default}` 플레이스홀더를 쓰되, relaxed binding(`PAYMENT_GATEWAY_URL` → `payment.gateway.url`, `ORDER_PAYMENT_TTL` → `order.payment.ttl`)으로도 동일하게 동작.
- `@ConfigurationProperties("order.payment") record OrderPaymentProperties(Duration ttl)`, `@ConfigurationProperties("payment.gateway") record PaymentGatewayProperties(String url, Duration timeout)`; `@EnableConfigurationProperties`.
- 테스트(Testcontainers + 가짜 PG 스텁)는 `@DynamicPropertySource`/`@ServiceConnection`으로 `payment.gateway.url`, `order.payment.ttl=PT3S`, 필요 시 `order.expiry.scan-interval`을 덮어쓴다.
- `build.gradle`에 이미 web/validation/data-jpa/flyway/postgresql/testcontainers가 있다. 추가 의존성 불필요(PG 클라이언트는 JDK `HttpClient`).

---

## 8. [Q.] 질의 (기본값을 정해 진행 가능, 비차단)

- [Q.] PG 결제 요청 본문의 `orderId` 타입(숫자 vs 문자열) 계약이 불명. 기본 결정: 숫자(주문 응답 `id`와 동일 표현).
- [Q.] 결제 요청 시 이미 다른 결제가 PG 호출 진행 중인 `PENDING_PAYMENT` 주문에 대한 오류 코드. 표에 맞는 코드가 `INVALID_STATE`뿐이라 409 `INVALID_STATE`로 결정.
- [Q.] 0원 주문의 환불 시 PG 환불 호출 여부. 기본 결정: 호출하지 않음(결제 기록 없음).
- [Q.] `couponCode`가 빈 문자열일 때 의미. 기본 결정: 400.
- [Q.] 경로 변수 id 변환 실패(`/api/orders/abc`)를 400으로 할지 404로 할지. 기본 결정: 400 `VALIDATION_ERROR`.

---

# 정합 요약

상세 DDL·제약 이름·인덱스: `02_db_design.md`. 이 절이 구현·검증의 기준이며 02에서는 복제하지 않는다.

## (a) 응답 필드 ↔ 컬럼 대응표

### ProductResponse (`products`)
| 응답 필드 | JSON 타입 | 컬럼 / 계산 | DB 타입 | 비고 |
|---|---|---|---|---|
| id | number | `products.id` | BIGSERIAL | |
| name | string | `products.name` | VARCHAR(100) NOT NULL | 원문 보존 |
| price | number(long) | `products.price` | BIGINT NOT NULL | 1~10,000,000 |
| stock | number(int) | `products.stock` | INTEGER NOT NULL | ≥0 |
| reserved | number(int) | `products.reserved` | INTEGER NOT NULL DEFAULT 0 | 0≤reserved≤stock |
| available | number(int) | `stock - reserved` (계산) | — | 컬럼 없음 |
| (미노출) | — | `products.created_at` | TIMESTAMPTZ NOT NULL | |

### CouponResponse (`coupons`)
| 응답 필드 | JSON 타입 | 컬럼 | DB 타입 | 비고 |
|---|---|---|---|---|
| code | string | `coupons.code` | VARCHAR(20) UNIQUE NOT NULL | |
| type | string | `coupons.type` | VARCHAR(10) NOT NULL | FIXED/RATE |
| value | number(long) | `coupons.value` | BIGINT NOT NULL | |
| minOrderAmount | number(long) | `coupons.min_order_amount` | BIGINT NOT NULL DEFAULT 0 | 요청 null→0 |
| maxDiscountAmount | number(long)\|null | `coupons.max_discount_amount` | BIGINT NULL | null=무제한 |
| totalQuantity | number(int) | `coupons.total_quantity` | INTEGER NOT NULL | ≥1 |
| usedCount | number(int) | `coupons.used_count` | INTEGER NOT NULL DEFAULT 0 | 0≤used≤total |
| validFrom | string(ISO,UTC) | `coupons.valid_from` | TIMESTAMPTZ NOT NULL | |
| validUntil | string(ISO,UTC) | `coupons.valid_until` | TIMESTAMPTZ NOT NULL | from<until |
| (미노출) | — | `coupons.id`, `coupons.created_at` | BIGSERIAL, TIMESTAMPTZ | |

### OrderResponse (`orders`, `order_items`)
| 응답 필드 | JSON 타입 | 컬럼 | DB 타입 | 비고 |
|---|---|---|---|---|
| id | number | `orders.id` | BIGSERIAL | |
| userId | string | `orders.user_id` | VARCHAR(50) NOT NULL | |
| status | string | `orders.status` | VARCHAR(20) NOT NULL | 8종 CHECK |
| items[].productId | number | `order_items.product_id` | BIGINT NOT NULL FK | |
| items[].quantity | number(int) | `order_items.quantity` | INTEGER NOT NULL | 1~1000 |
| items[].unitPrice | number(long) | `order_items.unit_price` | BIGINT NOT NULL | 주문 시점 스냅샷 |
| (items 순서) | — | `order_items.line_no` | INTEGER NOT NULL | 요청 순서 |
| couponCode | string\|null | `orders.coupon_code` | VARCHAR(20) NULL | |
| subtotal | number(long) | `orders.subtotal` | BIGINT NOT NULL | |
| discount | number(long) | `orders.discount` | BIGINT NOT NULL | 0≤discount≤subtotal |
| totalPrice | number(long) | `orders.total_price` | BIGINT NOT NULL | =subtotal−discount |
| createdAt | string(ISO,UTC) | `orders.created_at` | TIMESTAMPTZ NOT NULL | µs 절단 |
| expiresAt | string(ISO,UTC) | `orders.expires_at` | TIMESTAMPTZ NOT NULL | =created_at+TTL |
| paidAt | string\|null | `orders.paid_at` | TIMESTAMPTZ NULL | |
| (미노출) | — | `orders.coupon_id` | BIGINT NULL FK | 락·복원용 |
| (미노출) | — | `orders.payment_id` | VARCHAR(100) NULL | PG 환불용 |
| (미노출) | — | `orders.gateway_inflight_since` | TIMESTAMPTZ NULL | PG 호출 선점 표식 |
| (미노출) | — | `orders.updated_at` | TIMESTAMPTZ NOT NULL | |

### 목록·멱등
| 응답/요소 | 출처 |
|---|---|
| `content[]` | `OrderResponse`와 동일 |
| `nextCursor` | 컬럼 아님. 마지막 원소의 `(orders.created_at, orders.id)` → `base64url("v1|"+epochMicros+"|"+id)` ; 마지막 페이지면 null |
| (비노출) 멱등 응답 재생 | `idempotency_records.response_status/response_body/response_location` |

### 요청 필드 필수 여부 ↔ nullable
| 요청 필드 | 필수 | 컬럼 nullable |
|---|---|---|
| product.name/price/stock | 필수 | NOT NULL |
| coupon.code/type/value/totalQuantity/validFrom/validUntil | 필수 | NOT NULL |
| coupon.minOrderAmount | 선택(→0) | NOT NULL DEFAULT 0 |
| coupon.maxDiscountAmount | 선택(→무제한) | NULL |
| order.items[].productId/quantity | 필수 | NOT NULL |
| order.couponCode | 선택 | `coupon_id`, `coupon_code` NULL |
| pay.cardToken | 필수 | 저장하지 않음(PG 전달만) — 민감 필드, 응답·DB·로그에 남기지 않음 |

## (b) 제약 위반 ↔ HTTP 상태 / code 매핑표

| 위반/상황 | 검출 위치 | HTTP | code | 우선순위 단계 |
|---|---|---|---|---|
| 본문/헤더/쿼리/경로 검증·변환·파싱 실패 | 컨트롤러 검증 | 400 | `VALIDATION_ERROR` | 1 |
| 같은 키 + 다른 (user, 경로, 본문) | `idempotency_records` 지문 비교 | 422 | `IDEMPOTENCY_KEY_MISMATCH` | 2 |
| 같은 키 처리 중(`IN_PROGRESS`) | `uk_idempotency_scope_key` 충돌 후 상태 확인 | 409 | `IDEMPOTENCY_IN_PROGRESS` | 2 |
| 같은 키 + 같은 요청 완료됨 | 동일 | 최초 상태(200/201) | (재생, 오류 아님) | 2 |
| 상품 id 없음 (주문 생성 / GET 상품) | 상품 잠금 조회 / 조회 | 404 | `PRODUCT_NOT_FOUND` | 3 |
| 쿠폰 code 없음 (주문 생성 / GET 쿠폰) | 쿠폰 잠금 조회 / 조회 | 404 | `COUPON_NOT_FOUND` | 3 |
| 주문 id 없음 | 주문 조회/잠금 | 404 | `ORDER_NOT_FOUND` | 3 |
| `stock - reserved < quantity` (방어선: `chk_products_reserved`) | 락 후 검사 | 409 | `INSUFFICIENT_STOCK` | 4a |
| 쿠폰 기간 밖 / subtotal < min_order_amount | 락 후 검사 | 409 | `COUPON_NOT_APPLICABLE` | 4b |
| 동일 사용자 사용 중 주문 존재 (최종 방어선 `uk_orders_user_coupon_in_use`) | 락 후 조회 + 유니크 위반 catch | 409 | `COUPON_NOT_APPLICABLE` | 4b |
| `used_count >= total_quantity` (방어선: `chk_coupons_used_count`) | 락 후 검사 | 409 | `COUPON_EXHAUSTED` | 4b (NOT_APPLICABLE 뒤) |
| 쿠폰 code 중복 (`uk_coupons_code`) | 사전 확인 + 유니크 위반 catch | 409 | `DUPLICATE_COUPON_CODE` | (쿠폰 등록은 검증 400 뒤) |
| 주문 상태가 PENDING 아님 / 만료됨 / PG 호출 진행 중 (결제) | 주문 락 후 상태 검사 | 409 | `INVALID_STATE` | 4 |
| 취소·ship·deliver의 불가 상태 / 진행 중 | 주문 락 후 상태 검사 | 409 | `INVALID_STATE` | 4 |
| PG DECLINED | PG 응답 | 402 | `PAYMENT_DECLINED` | 5 (주문→PAYMENT_FAILED+복원) |
| PG 5xx / 연결 실패 / 2초 초과 / 계약 외 응답(4xx 등), 환불 포함 | `PaymentGatewayClient` | 503 | `PAYMENT_GATEWAY_UNAVAILABLE` | 5 (상태 불변) |
| `uk_order_items_order_product`, FK(`order_items.product_id`, `orders.coupon_id`) | 검증(중복 400)·락 조회(404)에서 선차단 | (정상 경로에선 미발생) | — | — |
| 기타 DB CHECK 위반(`chk_*`) | 설계상 도달 불가(방어선) | 500 | `INTERNAL_ERROR` | — |
