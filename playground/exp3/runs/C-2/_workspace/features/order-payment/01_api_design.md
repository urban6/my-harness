# order-payment — API 설계 (Phase 1)

스택: Java 21 / Spring Boot 3.5.x / Spring Data JPA + Hibernate + PostgreSQL / Flyway / JUnit 5 + Testcontainers. 기존 코드는 `OrderApplication`(패키지 `com.example.order`)뿐인 그린필드라 새 관례를 세운다. DB 상세는 `02_db_design.md`.
각 섹션 제목의 `[REQ-xx]`는 그 요구를 충족하는 위치다. 전체 매핑은 "REQ 추적표".

---

## 0. 확정 결정 (feature-short.md 미정 항목) — 근거 한 줄씩

| ID | 결정 | 근거 |
|---|---|---|
| D-01 | 취소(CANCELLED)·만료(EXPIRED)·결제실패(PAYMENT_FAILED) 종결 시 **재고 예약 해제 + 쿠폰 used_count 복구**. | 결제되지 않은 종결은 자원을 점유할 이유가 없다. REQ-12가 취소 시 쿠폰 복구를 명시. |
| D-02 | 환불(REFUNDED)은 **재고(stock)만 복구, 쿠폰은 복구하지 않는다**. | REQ-12가 환불에 재고 복구만 명시. 결제 완료 건의 쿠폰 재사용(환불 후 재사용 악용)을 막는다. |
| D-03 | 멱등 키 충돌(같은 키 + 다른 본문)은 **409** `idempotency-key-reused`. | REQ-15가 멱등 충돌을 409로 분류. 422는 쓰지 않는다. |
| D-04 | PG 타임아웃(연결/읽기) → **504** `payment-gateway-timeout`. 연결 실패·PG 5xx·PG 4xx·응답 형식 오류 → **502** `payment-gateway-error`. | REQ-11/15의 502/504 구분 그대로. 둘 다 `retryable=true`. |
| D-05 | 목록 `size` 기본 **20**, 최대 **100**, 최소 1. 범위 밖은 clamp하지 않고 **400**. | REQ-09가 잘못된 size는 400. 조용한 보정은 클라이언트 버그를 숨긴다. |
| D-06 | 환불 PG 호출 실패(502/504) 시 **주문은 PAID 유지**, 재고·결제 기록 불변, 같은 `cancel`을 재시도하면 된다. 별도 REFUND_FAILED 상태 없음. | REQ-12 "PG 환불 실패 시 상태 유지". 상태 enum은 스펙의 8개로 고정. |
| D-07 | 같은 Idempotency-Key 동시 요청은 **직렬화 후 리플레이**. 후행 요청은 선행 트랜잭션 커밋을 기다렸다가 같은 결과를 받는다(별도 "처리 중" 오류 없음). 선행이 실패(롤백)하면 후행이 새로 처리한다. | PG의 unique 인덱스 대기 + 주문 행 락으로 구현이 단순하고 REQ-17(단일 주문)을 보장. |
| D-08 | 만료는 **스케줄러 + lazy 병행**. 스케줄러(기본 10초 주기)가 일괄 처리, `GET /orders/{id}`·`pay`·`cancel`·`ship`·`deliver`·목록 조회 시 해당 주문을 즉시 만료 처리한다. | 상태가 DB에 영속 반영되고 호출 시점 지연이 없다. 테스트는 TTL을 짧게 잡고 조회만으로 검증 가능. |
| D-09 | PG 호출은 **주문 행 `SELECT ... FOR UPDATE` 락을 잡은 트랜잭션 안에서** 수행한다(타임아웃: 연결 2s / 읽기 5s). | 결제·취소·만료·출고 경합을 락 하나로 직렬화 → 일관된 최종 상태(REQ-17). 락 보유는 PG 타임아웃 이내로 제한. |
| D-10 | 결제 거절(DECLINED)은 오류가 아니라 **200 + 주문(status=PAYMENT_FAILED)**. | 비즈니스 결과이며 REQ-15의 오류 목록에 거절 코드가 없다. |
| D-11 | 멱등 재생 응답: 주문 생성은 **원 응답과 같은 201 + Location**, 결제는 **200 + 주문의 현재 상태**. 둘 다 `Idempotent-Replayed: true` 헤더 추가. | 클라이언트가 재시도와 최초 처리를 구분하되 본문 파싱 로직은 동일. |
| D-12 | 멱등성 키 범위: 주문 생성 = `(CREATE_ORDER, X-User-Id, key)`, 결제 = `(PAY, 전역, key)`. 결제 키가 다른 주문에 재사용되면 409. | 결제 요청에 X-User-Id가 없다. 키를 PG에 그대로 전달하므로 전역 유일해야 PG에서 다른 주문 결과가 섞이지 않는다. |
| D-13 | 4xx/5xx로 실패한 요청의 멱등 키는 **저장하지 않는다**(트랜잭션 롤백). 같은 키로 재시도하면 새로 평가된다. 성공(생성 / PG 승인·거절)만 저장. | PG 장애 후 같은 키로 재시도 가능해야 한다(REQ-11). 일시 오류를 영구 고정하지 않는다. |
| D-14 | 쿠폰 할인: FIXED = `min(value, subtotal)`, RATE = `min(floor(subtotal*value/100), maxDiscountAmount(있으면), subtotal)`. FIXED의 `maxDiscountAmount`는 저장만 하고 계산에 쓰지 않는다. | "discount > subtotal 불가"를 clamp로 해석. 스펙이 상한을 RATE에만 명시. |
| D-15 | 쿠폰 오류: 쿠폰 없음 404, 유효기간 밖·최소금액 미달·소진 → **409** `coupon-not-applicable`(`reason` 확장). | REQ-15 "쿠폰 충돌 409", 미존재 404. |
| D-16 | `items`에서 같은 productId 중복은 **400**. items 1~50개, quantity 1~100000. | 병합보다 명시적. 상한은 금액 long 오버플로 방지. |
| D-17 | `X-User-Id`는 **주문 생성에서만 필수**(1~64자 문자열, JSON에서도 문자열). 그 외 엔드포인트는 헤더 불요. 목록의 `userId`, `status`는 선택 필터. | 스펙 표가 생성에만 헤더를 명시. 인증 없음. |
| D-18 | 환불 후에도 `paidAt`은 유지한다. `paymentId`는 DB에 저장하되 주문 응답 스키마에는 노출하지 않는다. | 스펙 주문 객체 필드 고정. paymentId 검증은 DB(payments 테이블)로. |
| D-19 | Problem Details `type` = `https://example.com/problems/{slug}`(kebab-case), 확장 필드 `code`(UPPER_SNAKE) 상시 포함. | RFC 9457은 type을 URI로 요구. 기계 판독용 `code`로 분기 단순화. |
| D-20 | 쿠폰은 사용자당 1회 제한이 없다. | 스펙에 없음. 필요 시 후속 요구. |

---

## 1. 공통 규칙

- Base path `/api`, JSON(`application/json`), 오류는 `application/problem+json`.
- 시각: ISO-8601 UTC(`2026-10-10T12:00:00.123456Z`). 저장 정밀도(마이크로초)에 맞춰 생성 시 `Instant`를 마이크로초로 절삭한다. `Clock` 빈을 주입해 시간을 얻는다(테스트 제어).
- 금액: **정수 원(KRW) `long`/BIGINT**. 소수 금액은 400. Jackson `accept-float-as-int=false`로 `10.5`→long 절삭을 막는다.
- ID: Long(숫자, JSON number). `userId`: 문자열.
- 트랜잭션 격리: PostgreSQL 기본 READ COMMITTED. 동시성은 조건부 UPDATE와 행 락으로 보장(02 문서 §3).
- Location: `ServletUriComponentsBuilder` 기준 절대 URL. 상품 `/api/products/{id}`, 쿠폰 `/api/coupons/{code}`, 주문 `/api/orders/{id}`.

### 1.1 스키마 정의

**Product**
```json
{ "id": 1, "name": "keyboard", "price": 30000, "stock": 10, "reserved": 2, "available": 8 }
```
`available = stock - reserved`(계산 값, 컬럼 없음).

**Coupon**
```json
{ "code": "WELCOME10", "type": "RATE", "value": 10, "minOrderAmount": 10000, "maxDiscountAmount": 5000,
  "totalQuantity": 100, "validFrom": "2026-01-01T00:00:00Z", "validUntil": "2026-12-31T00:00:00Z", "usedCount": 3 }
```

**Order**
```json
{ "id": 7, "userId": "u-1", "status": "PENDING_PAYMENT",
  "items": [ { "productId": 1, "quantity": 2, "unitPrice": 30000 } ],
  "couponCode": "WELCOME10", "subtotal": 60000, "discount": 5000, "totalPrice": 55000,
  "createdAt": "2026-10-10T12:00:00Z", "expiresAt": "2026-10-10T12:15:00Z", "paidAt": null }
```
`items`는 요청 순서(order_items.id 오름차순). `couponCode`·`paidAt`은 없으면 `null`(필드 생략하지 않음).

---

## 2. 엔드포인트

### 2.1 POST /api/products [REQ-01]
요청: `{name, price, stock}`
- name: 필수, 공백 불가, 1~200자
- price: 필수, 0 ≤ price ≤ 1,000,000,000 (정수)
- stock: 필수, 0 ≤ stock ≤ 1,000,000,000 (정수)

응답: **201** + `Location: /api/products/{id}` + Product(reserved=0, available=stock).
오류: 400 `validation-failed`(필드별 `errors[]`), 400 `malformed-request`(JSON 파싱/타입 불일치).

### 2.2 GET /api/products/{id} [REQ-02]
응답 200 Product. 없으면 404 `product-not-found`. `id`가 숫자가 아니면 400 `invalid-parameter`.

### 2.3 POST /api/coupons [REQ-03]
요청: `{code, type, value, minOrderAmount?, maxDiscountAmount?, totalQuantity, validFrom, validUntil}`
- code: 필수, 1~50자, `^[A-Za-z0-9_-]+$`, 대소문자 구분
- type: 필수, `FIXED|RATE` (그 외 400 `malformed-request`)
- value: 필수, ≥1; type=RATE이면 1~100; FIXED는 ≤ 1,000,000,000
- minOrderAmount: 선택(null → 0), ≥0
- maxDiscountAmount: 선택(null = 상한 없음), 지정 시 ≥1
- totalQuantity: 필수, 1~1,000,000,000
- validFrom, validUntil: 필수, 오프셋 포함 ISO-8601, `validFrom < validUntil` (위반 시 `errors[].field="validUntil"`)

응답: **201** + `Location: /api/coupons/{code}` + Coupon(`usedCount=0`; minOrderAmount는 null이 들어왔어도 0으로 응답).
오류: 400 `validation-failed`; **409 `coupon-code-duplicated`**(unique 위반, 확장 `couponCode`). 중복 판정은 사전 조회가 아니라 `uq_coupons_code` 위반을 `saveAndFlush`에서 잡아 변환(레이스 안전).

### 2.4 GET /api/coupons/{code} [REQ-04]
응답 200 Coupon(요청 필드 + `usedCount`). 없으면 404 `coupon-not-found`.

### 2.5 POST /api/orders [REQ-05, REQ-06, REQ-07, REQ-17]
헤더: `X-User-Id`(필수, 1~64자 non-blank), `Idempotency-Key`(필수, 1~128자, 출력 가능 ASCII `\x21-\x7E`).
본문: `{items:[{productId, quantity}], couponCode?}` — items 1~50개, productId ≥1 중복 불가, quantity 1~100000. couponCode가 빈 문자열이면 400, `null`/생략이면 쿠폰 없음.

**처리 순서(단일 트랜잭션, 하나라도 실패하면 전체 롤백):**
1. 헤더/본문 검증 → 400.
2. `request_hash` 계산 = SHA-256(정규화 본문: items를 productId 오름차순 `productId:quantity` 나열 + `|` + couponCode(없으면 빈 문자열)). items 순서가 달라도 같은 본문으로 본다.
3. **멱등 키 선점**: `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` (operation=CREATE_ORDER, scope_key=userId, key).
   - 삽입 성공 → 신규 처리 계속.
   - 0행(이미 존재, 동시 선행 요청이면 선행 커밋/롤백까지 대기) → 기존 행 조회: `request_hash` 같음 → 해당 주문을 **201 + Location + `Idempotent-Replayed: true`**로 반환(재고·쿠폰 불변). 다르면 **409 `idempotency-key-reused`**.
4. 상품 조회(productId 오름차순): 하나라도 없으면 404 `product-not-found`(`productId`). 이 시점에는 아무것도 변경하지 않았다.
5. 쿠폰 지정 시: 조회(없으면 404 `coupon-not-found`) → 사전 검사(유효기간 `validFrom ≤ now < validUntil`, `subtotal ≥ minOrderAmount`, `usedCount < totalQuantity`) 위반 시 409 `coupon-not-applicable`(`reason`).
6. **재고 예약**(productId 오름차순으로): `UPDATE products SET reserved = reserved + :q WHERE id = :id AND stock - reserved >= :q` → 0행이면 409 `insufficient-stock`(`productId`,`requested`,`available`). 예외로 롤백되어 이전 항목 예약도 취소된다.
7. **쿠폰 사용 증가**(상품 뒤): `UPDATE coupons SET used_count = used_count + 1 WHERE code = :code AND used_count < total_quantity AND valid_from <= :now AND valid_until > :now` → 0행이면 409 `coupon-not-applicable`(`reason=EXHAUSTED`, 재조회로 판별).
8. 주문(`PENDING_PAYMENT`)·항목 INSERT. `unitPrice`=상품 가격 스냅샷, `subtotal=Σ unitPrice×quantity`, `discount`는 D-14, `totalPrice=subtotal-discount`, `createdAt=now`, `expiresAt=createdAt+ORDER_PAYMENT_TTL`(생성 시점 값을 컬럼에 고정). 멱등 행에 `order_id`, `outcome=CREATED` 기록.

응답: **201** + `Location: /api/orders/{id}` + Order.
오류: 400(`missing-required-header` `header="X-User-Id"|"Idempotency-Key"`, `validation-failed`, `malformed-request`), 404(`product-not-found`,`coupon-not-found`), 409(`insufficient-stock`,`coupon-not-applicable`,`idempotency-key-reused`).
락 순서: **products(id 오름차순) → coupons**. 모든 변경 경로가 같은 순서라 교착이 없다(02 §3).

### 2.6 GET /api/orders/{id} [REQ-08, REQ-13]
응답 200 Order. 없으면 404 `order-not-found`.
조회 전 lazy 만료(§7): `PENDING_PAYMENT && expiresAt ≤ now`이면 **영속적으로 EXPIRED 전환 + 재고·쿠폰 복구 후** 반환. 해당 행이 결제 처리 중(락 보유)이면 `SKIP LOCKED`로 건너뛰고 현재 상태를 그대로 반환한다.

### 2.7 GET /api/orders?userId&status&size&cursor [REQ-09]
쿼리: `userId`(선택, 1~64자), `status`(선택, 8개 enum 중 하나), `size`(선택, 기본 20, 1~100), `cursor`(선택).
응답 200:
```json
{ "content": [ /* Order 전체 스키마, id 내림차순 */ ], "nextCursor": "bzoxMjM" }
```
- **정렬키**: `orders.id DESC`(단조 증가 PK → 중복/누락 없는 안정 정렬; 동시 INSERT에도 이미 본 구간이 흔들리지 않음).
- **커서**: 마지막으로 반환한 항목의 id를 `base64url_nopad("o:" + id)`로 인코딩한 불투명 문자열. 다음 페이지는 `WHERE id < :cursorId`. 디코딩 실패·접두사 불일치·id ≤ 0이면 400 `invalid-parameter`(`parameter="cursor"`).
- **hasNext 판별**: `LIMIT size+1`로 조회해 초과분이 있으면 `nextCursor`=마지막 반환 항목의 커서, 없으면(마지막 페이지·빈 결과) `null`.
- 잘못된 `size`(0, 음수, 101 이상, 비숫자)·잘못된 `status` → 400 `invalid-parameter`(`parameter`).
- 필터 조합이 바뀐 커서는 별도 검증하지 않는다(커서는 id 위치만 의미).
- 조회 전 만료 스윕(§7)을 최대 100건 수행하여 목록이 EXPIRED를 반영한다.
- 구현: `items`는 N+1을 피하도록 배치 조회(`IN` 또는 `@BatchSize`).

### 2.8 POST /api/orders/{id}/pay [REQ-10, REQ-11, REQ-17]
헤더: `Idempotency-Key`(필수, 1~128자). 본문: `{cardToken}`(필수, 공백 불가, 1~200자). cardToken은 **저장·로깅하지 않는다**.

**처리(단일 트랜잭션, 주문 행 `FOR UPDATE` 후 PG 호출):**
1. 검증 → 400. 주문 행 락 획득(없으면 404 `order-not-found`).
2. 멱등 조회 `(PAY, *, key)`:
   - 존재 + `request_hash`(= SHA-256(`orderId|cardToken`)) 일치 → 저장된 결과 리플레이: **200 + 주문 현재 상태 + `Idempotent-Replayed: true`**, **PG 호출 없음**.
   - 존재 + 해시 불일치(다른 본문 또는 다른 주문에 사용된 키) → 409 `idempotency-key-reused`.
3. 상태 검사: `PENDING_PAYMENT`가 아니면 → 상태가 `EXPIRED`면 409 `order-expired`, 그 외 409 `invalid-order-state`(`currentStatus`).
4. 만료 검사: `expiresAt ≤ now`이면 **EXPIRED 전환·복구를 커밋한 뒤** 409 `order-expired`(예외는 트랜잭션 커밋 후 던지거나 `noRollbackFor`로 처리해 전환이 롤백되지 않게 한다).
5. 멱등 키 선점(INSERT ON CONFLICT DO NOTHING; 0행이면 다른 주문의 동시 사용 → 409 `idempotency-key-reused`).
6. PG 호출(§5). 실패(타임아웃/5xx 등)는 예외 → **트랜잭션 롤백**: 주문·재고·멱등 키 모두 변경 없음, 클라이언트는 같은 키로 재시도.
7. 결과 반영:
   - `APPROVED` → `status=PAID`, `paidAt=now`, payments(APPROVED, paymentId, amount) INSERT, 상품별 `UPDATE products SET stock = stock - :q, reserved = reserved - :q WHERE id=:id AND reserved >= :q AND stock >= :q`(id 오름차순). 쿠폰은 소진 상태 유지.
   - `DECLINED` → `status=PAYMENT_FAILED`, payments(DECLINED) INSERT, 재고 예약 해제(`reserved -= q`), 쿠폰 `used_count -= 1`(D-01).
   - 멱등 행에 `outcome=APPROVED|DECLINED`, `order_id` 기록.

응답: **200** + Order (`APPROVED`→`PAID`, `DECLINED`→`PAYMENT_FAILED`).
오류: 400, 404 `order-not-found`, 409(`invalid-order-state`,`order-expired`,`idempotency-key-reused`), 502 `payment-gateway-error`, 504 `payment-gateway-timeout`.

### 2.9 POST /api/orders/{id}/cancel [REQ-12, REQ-17]
헤더/본문 없음. 주문 행 `FOR UPDATE` 후:
- lazy 만료 검사 후에도 `PENDING_PAYMENT`면 → `CANCELLED`, 재고 예약 해제, 쿠폰 복구(D-01).
- `PAID` → payments의 `paymentId`로 PG 환불 호출(§5.3). 성공(`REFUNDED`) → 주문 `REFUNDED`, payments `REFUNDED`+`refundedAt`, 상품별 `stock += q`(재고 복구; reserved는 이미 결제 시 차감됨), 쿠폰은 복구하지 않음(D-02). 환불 실패(502/504) → 롤백, **PAID 유지**(D-06).
- 그 외(`SHIPPED`,`DELIVERED`,`PAYMENT_FAILED`,`EXPIRED`,`CANCELLED`,`REFUNDED`) → 409 `invalid-order-state`. 같은 주문의 취소 재호출도 409.

응답: **200** + Order. 오류: 404, 409, 502, 504.

### 2.10 POST /api/orders/{id}/ship, /deliver [REQ-14]
주문 행 `FOR UPDATE` 후 전이 검사. `ship`: `PAID → SHIPPED`, `deliver`: `SHIPPED → DELIVERED`. 그 외 상태는 409 `invalid-order-state`. 응답 **200** + Order. 404 없음. (PG 호출 없음, 멱등 키 없음 — 재호출은 409.)

---

## 3. 주문 상태 전이표 [REQ-14, REQ-12, REQ-13, REQ-10]

| 현재 상태 | 이벤트 | 다음 상태 | 부수 효과 |
|---|---|---|---|
| (신규) | 주문 생성 | PENDING_PAYMENT | reserved += q, 쿠폰 used_count += 1 |
| PENDING_PAYMENT | pay → PG APPROVED | PAID | paidAt 기록, stock -= q, reserved -= q |
| PENDING_PAYMENT | pay → PG DECLINED | PAYMENT_FAILED | reserved -= q, 쿠폰 복구 |
| PENDING_PAYMENT | cancel | CANCELLED | reserved -= q, 쿠폰 복구 |
| PENDING_PAYMENT | TTL 경과(스케줄러/lazy) | EXPIRED | reserved -= q, 쿠폰 복구 |
| PAID | cancel → PG 환불 성공 | REFUNDED | stock += q (쿠폰 복구 없음) |
| PAID | cancel → PG 환불 실패 | PAID (유지) | 변화 없음, 502/504 |
| PAID | ship | SHIPPED | — |
| SHIPPED | deliver | DELIVERED | — |

종결 상태: `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`, `REFUNDED`, `DELIVERED`. **위 표에 없는 모든 (상태, 이벤트) 조합은 409 `invalid-order-state`** (`currentStatus`, `requestedAction` 확장). 전이는 항상 주문 행 락 아래에서 현재 상태를 재확인한 뒤 수행한다.

---

## 4. Problem Details 오류 계약 [REQ-15]

- Content-Type `application/problem+json`. Spring `spring.mvc.problemdetails.enabled=true` + `@RestControllerAdvice`(`ResponseEntityExceptionHandler` 확장)로 프레임워크 예외(405, 415, 400 바인딩/파싱)도 동일 포맷.
- 표준 필드: `type`, `title`, `status`, `detail`, `instance`(요청 경로, 쿼리 제외, 예 `/api/orders/7`).
- 공통 확장 필드: `code` — 항상 포함, `type` slug의 UPPER_SNAKE(예 `INSUFFICIENT_STOCK`).
- `type` 규칙: `https://example.com/problems/{slug}` (slug = kebab-case, 아래 표). 알 수 없는 경우 `about:blank`는 쓰지 않는다.
- 500은 내부 정보(스택·SQL)를 `detail`에 노출하지 않는다(고정 문구, 서버 로그에만 상세).

| slug (code) | status | 발생 | 확장 필드 |
|---|---|---|---|
| validation-failed (VALIDATION_FAILED) | 400 | Bean Validation 위반 | `errors:[{field,message}]` |
| malformed-request (MALFORMED_REQUEST) | 400 | JSON 파싱 오류, enum/타입 불일치 | — |
| missing-required-header (MISSING_REQUIRED_HEADER) | 400 | `X-User-Id`/`Idempotency-Key` 누락·공백 | `header` |
| invalid-parameter (INVALID_PARAMETER) | 400 | 경로·쿼리 변환 실패, 잘못된 cursor/size/status | `parameter` |
| product-not-found | 404 | 상품 없음(조회·주문 항목) | `productId` |
| coupon-not-found | 404 | 쿠폰 없음 | `couponCode` |
| order-not-found | 404 | 주문 없음 | `orderId` |
| resource-not-found | 404 | 매핑 안 된 경로 | — |
| method-not-allowed / unsupported-media-type | 405 / 415 | 프레임워크 | — |
| coupon-code-duplicated | 409 | 쿠폰 code unique 위반 | `couponCode` |
| insufficient-stock | 409 | 가용 재고 부족 | `productId`,`requested`,`available` |
| coupon-not-applicable | 409 | 쿠폰 조건 불충족 | `couponCode`,`reason`: `NOT_YET_VALID`·`EXPIRED`·`BELOW_MIN_ORDER_AMOUNT`·`EXHAUSTED` |
| idempotency-key-reused | 409 | 같은 키·다른 본문(또는 결제 키가 다른 주문에 재사용) | `idempotencyKey` |
| invalid-order-state | 409 | 허용되지 않는 전이 | `orderId`,`currentStatus`,`requestedAction` |
| order-expired | 409 | 만료된 주문에 pay | `orderId`,`expiresAt` |
| payment-gateway-error | 502 | PG 연결 실패·5xx·4xx·응답 형식 오류 | `reason`: `UNAVAILABLE`·`SERVER_ERROR`·`REJECTED`·`BAD_RESPONSE`, `retryable:true` |
| payment-gateway-timeout | 504 | PG 연결/읽기 타임아웃 | `retryable:true` |
| internal-error | 500 | 예기치 못한 예외(`Exception` 캐치올) | — |

예시:
```json
{ "type": "https://example.com/problems/insufficient-stock", "title": "Insufficient stock", "status": 409,
  "detail": "Product 1 has 3 available but 5 requested.", "instance": "/api/orders",
  "code": "INSUFFICIENT_STOCK", "productId": 1, "requested": 5, "available": 3 }
```

---

## 5. 외부 PG 연동 계약 [REQ-10, REQ-11, REQ-12]

설정: `PAYMENT_GATEWAY_URL`(기본 `http://localhost:9090`), 연결 타임아웃 `PT2S`, 읽기 타임아웃 `PT5S`(`payment.gateway.*`로 오버라이드 가능). HTTP 클라이언트는 `RestClient`(JDK `HttpClient` 또는 타임아웃 설정 가능한 request factory). 내부 자동 재시도 없음(락 보유 시간 제한, 재시도는 클라이언트가 같은 키로).

### 5.1 승인 `POST {PG}/v1/payments`
- 헤더: `Idempotency-Key: <클라이언트가 보낸 pay 키 그대로>`, `Content-Type: application/json`
- 본문: `{"orderId": <주문 id, JSON number>, "amount": <totalPrice, long>, "cardToken": "<token>"}`
- 기대 응답: 200 `{"paymentId": "<string>", "status": "APPROVED" | "DECLINED"}`
- `totalPrice=0`(쿠폰으로 전액 할인)이어도 동일하게 PG를 호출한다.

### 5.2 장애 분류 (주문 상태 불변, 롤백)
| PG 측 상황 | 우리 응답 |
|---|---|
| 읽기/연결 타임아웃 | 504 `payment-gateway-timeout` |
| 연결 거부·DNS·I/O 오류(응답 전) | 502 `payment-gateway-error` reason=UNAVAILABLE |
| HTTP 5xx | 502 reason=SERVER_ERROR |
| HTTP 4xx | 502 reason=REJECTED (우리 요청 문제이므로 클라이언트 오류가 아님) |
| 200이지만 본문 불량(status 불명, paymentId 누락) | 502 reason=BAD_RESPONSE |

이중 결제 방지: 장애 시 아무것도 커밋하지 않고, 재시도는 **같은 Idempotency-Key로 PG에 재전송**되므로 PG가 중복 승인을 막는다. 알려진 한계(§9 L-1) 참조.

### 5.3 환불 `POST {PG}/v1/payments/{paymentId}/refund`
- 본문 없음. 보조 헤더 `Idempotency-Key: refund-{paymentId}`(스펙 외 추가, PG가 무시해도 무해).
- 기대 응답: 200 `{"paymentId": "...", "status": "REFUNDED"}`. 다른 status/형식/비-200은 §5.2와 동일 분류, 주문 PAID 유지.
- PG 환불은 `paymentId` 기준 멱등이라고 가정한다(타임아웃 후 재시도 가능).

---

## 6. 멱등성 규칙 요약 [REQ-07, REQ-11, REQ-17]

| 항목 | 주문 생성 | 결제 |
|---|---|---|
| 헤더 | `Idempotency-Key` 필수 | `Idempotency-Key` 필수 |
| 범위(D-12) | `(CREATE_ORDER, userId, key)` | `(PAY, 전역, key)` |
| 요청 해시 | 정렬된 items + couponCode | `orderId|cardToken` |
| 같은 키·같은 본문 | 기존 주문 201 + `Idempotent-Replayed: true` | 저장된 결과로 200 + 현재 주문, PG 미호출 |
| 같은 키·다른 본문 | 409 `idempotency-key-reused` | 409 `idempotency-key-reused` |
| 동시 같은 키 | unique 인덱스 대기 → 선행 커밋 후 리플레이 / 선행 롤백 시 후행이 신규 처리 | 주문 행 락으로 직렬화 → 선행 커밋 후 리플레이 / 선행이 PG 장애로 롤백되면 후행이 PG 재호출(같은 키라 PG가 중복 방지) |
| 저장 대상 | 생성 성공 | PG 승인·거절 결과 |
| 보관 기간 | 무기한(정리 잡 없음) | 동일 |
| X-User-Id 누락 | 400 | (해당 없음) |

---

## 7. 만료 처리 [REQ-13]

- **방식(D-08)**: 스케줄러 + lazy.
  - 스케줄러: `@Scheduled(fixedDelayString="${order.expiry-sweep-interval:PT10S}")`, `order.expiry-sweep-enabled`(기본 true)로 끌 수 있음(테스트 결정성). 서비스 메서드 `expireDueOrders(Instant now, int batch)`는 public으로 두어 테스트가 직접 호출할 수 있다.
  - 스윕 쿼리: `status='PENDING_PAYMENT' AND expires_at <= :now ORDER BY expires_at LIMIT 100` (부분 인덱스 사용). 주문별로 **별도 트랜잭션**에서 `FOR UPDATE SKIP LOCKED`로 잠그고 상태·만료를 **재검사**한 뒤 전이한다(결제 처리 중인 주문은 건너뛰고 다음 주기에 재처리, 다중 인스턴스에서도 안전).
  - lazy: `GET /orders/{id}`, `pay`, `cancel`, `ship`, `deliver`, 목록 조회 직전에 같은 `expireOne` 수행.
- 전이: `EXPIRED` + 재고 예약 해제 + 쿠폰 복구(D-01). 만료 후 `pay` → 409 `order-expired`.
- 만료 기준 시각은 주문 생성 시 고정된 `expires_at` 컬럼(TTL 환경 변수가 이후 바뀌어도 기존 주문 불변). `ORDER_PAYMENT_TTL`은 양수 ISO-8601 Duration(`Duration` 바인딩), 잘못된 값은 기동 실패.
- 결제 진행 중인 주문이 PG 응답 대기 중 만료 시각을 넘겨도, 만료 검사는 pay 시작 시점 기준이므로 승인되면 PAID가 된다.

---

## 8. 재고·쿠폰 복구 규칙 [REQ-05, REQ-06, REQ-12, REQ-13]

| 종결 | 재고 | 쿠폰 used_count |
|---|---|---|
| CANCELLED (PENDING에서) | reserved -= q | -= 1 |
| EXPIRED | reserved -= q | -= 1 |
| PAYMENT_FAILED | reserved -= q | -= 1 |
| PAID (승인) | stock -= q, reserved -= q | 유지 |
| REFUNDED (PAID에서) | stock += q | 유지(복구 없음, D-02) |

복구 SQL은 가드 포함: `reserved = reserved - :q WHERE reserved >= :q`, `used_count = used_count - 1 WHERE used_count > 0`. 0행이면 불변식 위반이므로 `IllegalStateException`(→500, 롤백). 복구는 항상 주문 행 락 아래의 상태 전이와 같은 트랜잭션에서, products(id 오름차순) → coupons 순으로 수행.

---

## 9. 알려진 한계

- L-1: PG가 승인했으나 우리 DB 커밋 직전 장애가 나면 주문은 PENDING_PAYMENT로 남는다. 클라이언트가 같은 키로 재시도하면 PG가 같은 결과를 반환해 복구된다. 재시도 없이 TTL이 지나면 PG만 승인된 상태가 되며 이는 별도 대사(reconciliation) 작업 영역이다(범위 밖).
- L-2: 인기 상품은 행 락으로 주문 생성이 직렬화된다(정합성 우선).
- L-3: 결제·환불 중에는 DB 커넥션과 주문 행 락을 PG 응답까지(최대 약 7초) 보유한다.
- L-4: 멱등 키 정리 잡 없음.
- 테스트 참고: `build.gradle`에 WireMock 등이 없으므로 PG 스텁은 JDK `com.sun.net.httpserver.HttpServer` 또는 의존성 추가로 구성하고, `PAYMENT_GATEWAY_URL`을 `@DynamicPropertySource`로 주입한다.

---

## 10. REQ 추적표

| REQ | 충족 위치 |
|---|---|
| REQ-01 | §2.1, §1.1 Product, 02 §2.1 products |
| REQ-02 | §2.2, available 계산(§1.1) |
| REQ-03 | §2.3, D-19/§4 `coupon-code-duplicated`, 02 `uq_coupons_code` |
| REQ-04 | §2.4 |
| REQ-05 | §2.5 단계 4~8, 02 §3.1 조건부 UPDATE |
| REQ-06 | §2.5 단계 5·7, D-01/D-02/D-14/D-15, §8 |
| REQ-07 | §2.5 단계 2~3, §6, D-03/D-07/D-11/D-12, 02 §4 |
| REQ-08 | §2.6, §1.1 Order, 02 `expires_at` |
| REQ-09 | §2.7, D-05 |
| REQ-10 | §2.8, §5.1, D-10 |
| REQ-11 | §2.8 단계 2·6, §5.2, §6, D-04/D-13, 02 `payments` |
| REQ-12 | §2.9, §5.3, §8, D-02/D-06 |
| REQ-13 | §7, D-08, 02 부분 인덱스 |
| REQ-14 | §2.10, §3 |
| REQ-15 | §4, 정합 요약 (b) |
| REQ-16 | 02 전체 (DDL, application.yml, 금액 BIGINT) |
| REQ-17 | D-07/D-09, §2.5 락 순서, §2.8 단계 1, 02 §3 |

---

## 정합 요약

링크 기준 문서: 본 표가 구현·검증의 단일 기준이다(`02_db_design.md`는 이 표를 복제하지 않고 참조한다).

### (a) 응답 필드 ↔ 컬럼 대응표

**Product** (`products`)
| 응답 필드 | 컬럼 | 타입 | 비고 |
|---|---|---|---|
| id | products.id | BIGINT identity | |
| name | products.name | VARCHAR(200) NOT NULL | 요청 필수 ↔ NOT NULL |
| price | products.price | BIGINT NOT NULL | 정수 원 |
| stock | products.stock | INTEGER NOT NULL | |
| reserved | products.reserved | INTEGER NOT NULL DEFAULT 0 | |
| available | (계산) stock - reserved | — | 컬럼 없음 |

**Coupon** (`coupons`)
| 응답 필드 | 컬럼 | 타입 | 비고 |
|---|---|---|---|
| code | coupons.code | VARCHAR(50) UNIQUE NOT NULL | 경로 식별자 |
| type | coupons.coupon_type | VARCHAR(10) NOT NULL | FIXED/RATE |
| value | coupons.discount_value | BIGINT NOT NULL | RATE는 1~100 |
| minOrderAmount | coupons.min_order_amount | BIGINT NOT NULL DEFAULT 0 | 요청 선택 ↔ 기본 0 |
| maxDiscountAmount | coupons.max_discount_amount | BIGINT NULL | 요청 선택 ↔ nullable |
| totalQuantity | coupons.total_quantity | INTEGER NOT NULL | |
| validFrom | coupons.valid_from | TIMESTAMPTZ NOT NULL | |
| validUntil | coupons.valid_until | TIMESTAMPTZ NOT NULL | |
| usedCount | coupons.used_count | INTEGER NOT NULL DEFAULT 0 | 응답에만 포함 |

**Order** (`orders`, `order_items`)
| 응답 필드 | 컬럼 | 타입 | 비고 |
|---|---|---|---|
| id | orders.id | BIGINT identity | |
| userId | orders.user_id | VARCHAR(64) NOT NULL | X-User-Id |
| status | orders.status | VARCHAR(20) NOT NULL | 8개 enum CHECK |
| items[].productId | order_items.product_id | BIGINT NOT NULL FK products | |
| items[].quantity | order_items.quantity | INTEGER NOT NULL (>0) | |
| items[].unitPrice | order_items.unit_price | BIGINT NOT NULL | 주문 시점 스냅샷 |
| couponCode | orders.coupon_code | VARCHAR(50) NULL FK coupons(code) | 쿠폰 없으면 null |
| subtotal | orders.subtotal | BIGINT NOT NULL | |
| discount | orders.discount | BIGINT NOT NULL DEFAULT 0 | ≤ subtotal |
| totalPrice | orders.total_price | BIGINT NOT NULL | = subtotal - discount (CHECK) |
| createdAt | orders.created_at | TIMESTAMPTZ NOT NULL | |
| expiresAt | orders.expires_at | TIMESTAMPTZ NOT NULL | created_at + TTL 스냅샷 |
| paidAt | orders.paid_at | TIMESTAMPTZ NULL | 결제 전 null |
| (비노출) paymentId | payments.payment_id | VARCHAR(100) NOT NULL | D-18 |
| (비노출) cardToken | 저장 안 함 | — | 민감 값 |

멱등 키(`idempotency_keys`)는 응답에 노출하지 않는다.
**주문 목록** `content[]`=Order 위 표 동일, `nextCursor`는 컬럼 없음(마지막 `orders.id`의 인코딩).

### (b) 제약 위반 ↔ HTTP 상태코드 매핑표

| 제약 / 조건 | 사전 방어 | 응답 |
|---|---|---|
| 요청 필드 검증 위반 (name 공백, price<0, stock<0, quantity<1, RATE 범위, validFrom≥validUntil 등) — DB의 `ck_*` CHECK와 동일 규칙 | Bean Validation | 400 `validation-failed` |
| JSON 파싱/enum/타입 불일치 | Jackson | 400 `malformed-request` |
| 필수 헤더 누락 (`X-User-Id`, `Idempotency-Key`) | 헤더 바인딩 | 400 `missing-required-header` |
| 잘못된 cursor/size/status/경로 id | 파라미터 파싱 | 400 `invalid-parameter` |
| `uq_coupons_code` UNIQUE 위반 | `saveAndFlush` 예외 변환 | 409 `coupon-code-duplicated` |
| `uq_idempotency_scope` UNIQUE + 해시 일치 | ON CONFLICT DO NOTHING 후 조회 | 201/200 리플레이 (오류 아님) |
| `uq_idempotency_scope` UNIQUE + 해시 불일치 | 동일 | 409 `idempotency-key-reused` |
| `fk_order_items_product` / productId 미존재 | 사전 조회 | 404 `product-not-found` |
| `fk_orders_coupon` / couponCode 미존재 | 사전 조회 | 404 `coupon-not-found` |
| 주문/쿠폰/상품 id 조회 결과 없음 | 리포지토리 | 404 `order-not-found` / `coupon-not-found` / `product-not-found` |
| `ck_products_reserved_le_stock` 위반 (재고 부족) | 조건부 UPDATE 0행 | 409 `insufficient-stock` |
| `ck_coupons_used_le_total` 위반 (쿠폰 소진) | 조건부 UPDATE 0행 | 409 `coupon-not-applicable` (EXHAUSTED) |
| 쿠폰 유효기간·최소 주문액 | 사전 검사 + UPDATE 조건 | 409 `coupon-not-applicable` |
| `ck_orders_status` / 상태 전이 위반 | 락 아래 상태 재확인 | 409 `invalid-order-state` |
| 만료 주문 결제 | 락 아래 만료 검사 | 409 `order-expired` |
| `uq_payments_order` UNIQUE (주문당 결제 1건) | 상태 가드로 도달 불가 | 도달 시 500 `internal-error` |
| 그 외 CHECK 위반(`ck_orders_*`, `ck_order_items_*`, `ck_products_*` 등)이 DB까지 도달 | 사전 검증이 막아야 함 | 500 `internal-error` (버그 신호) |
| PG 타임아웃 | RestClient 타임아웃 | 504 `payment-gateway-timeout` |
| PG 연결 실패 / 5xx / 4xx / 형식 오류 | 응답 분류 | 502 `payment-gateway-error` |
| 그 외 예외 | `@ExceptionHandler(Exception)` | 500 `internal-error` |
