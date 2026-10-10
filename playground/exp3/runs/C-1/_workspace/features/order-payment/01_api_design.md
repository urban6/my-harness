# order-payment — API 설계 (01_api_design.md)

스택: Java 21 / Spring Boot 3.5.16 / Spring Data JPA + Hibernate + PostgreSQL / Flyway / JUnit 5 + Testcontainers.
기준 문서: `feature-short.md`, `00_requirements.json`(R01~R19, A1~A4). 데이터 모델·DDL·원자 갱신 SQL 패턴은 `02_db_design.md`.
기존 코드: `com.example.order.OrderApplication`(스켈레톤)뿐인 그린필드. 패키지 루트 `com.example.order`.

표기: "결정: …, 근거: …" = 모호/충돌 사안에 대한 확정. "[Q.]" = 사용자/PM 확인 권장 사항(모두 비차단 — 아래 기본 결정으로 구현 진행 가능).

---

## 0. 공통 규약

| 항목 | 결정 |
|---|---|
| 베이스 경로 | `/api` |
| Content-Type | 요청/응답 `application/json`, 오류 응답 `application/problem+json`. 본문이 필요한 POST에 다른 Content-Type이면 415. |
| 금액 타입 | 결정: 정수 원(KRW), DB `BIGINT`, Java `long`, JSON **정수 number**(문자열 아님, 소수점 없음). 근거: 소수 원 없음, 반올림 오차 제거, 비교·CHECK 단순. |
| 수량/재고 | DB `INTEGER`, Java `int`. |
| ID | 상품·주문 `id`는 `BIGINT IDENTITY`, JSON number. 쿠폰은 `code`(문자열)로 식별하며 응답에 내부 id를 노출하지 않는다. |
| 시각 | Java `Instant`, DB `TIMESTAMPTZ`, JSON ISO-8601 UTC(`2026-10-10T12:00:00.123456Z`). 입력은 오프셋 포함 ISO-8601 허용(`+09:00`), 내부는 UTC. 모든 `now`는 주입된 `Clock`에서 얻고 **마이크로초로 truncate**(PG 정밀도와 왕복 일치). DB `now()`는 비즈니스 판정에 쓰지 않는다(테스트 Clock 제어 가능하도록 파라미터로 전달). |
| null 직렬화 | null 필드는 **생략하지 않고 `null`로 출력**(`couponCode`, `paidAt`, `maxDiscountAmount`, `nextCursor`). 단 Problem Details는 null 확장 필드 생략. |
| 숫자 파싱 | `spring.jackson.deserialization.accept-float-as-int=false` — `price: 10.5`가 조용히 10으로 절삭되지 않고 400. 필수 숫자 DTO 필드는 **박스 타입(Long/Integer) + @NotNull**(누락이 0으로 해석되지 않도록). 알 수 없는 JSON 속성은 무시(Boot 기본). |
| 사용자 식별 | 결정: `X-User-Id`는 **주문 생성(POST /api/orders)에서만 필수**(1~64자, 공백 불가, 문자열). 그 외 엔드포인트는 읽지 않으며 **소유권 검사를 하지 않는다**. 근거: A2(인증 없음). `userId`는 JSON **문자열**로 입력·출력(숫자/문자 ID 모두 수용). |
| Idempotency-Key | 1~128자, 공백 불가, 인쇄 가능 ASCII(0x21~0x7E). 위반 400 `validation-failed`, 누락 400 `missing-header`. |
| Location | 결정: **상대 경로**(`/api/products/{id}`, `/api/coupons/{code}`, `/api/orders/{id}`). 근거: 정확 일치·endsWith 검증 모두 만족, 프록시 호스트 의존 없음. |
| 멱등 재생 응답 | 원 응답과 동일한 상태·본문 + 헤더 `Idempotent-Replayed: true`. |
| 트랜잭션 격리 | PostgreSQL 기본 READ COMMITTED 전제. 조건부 UPDATE 패턴(동시성 §3.7)은 이 격리 수준의 재평가 의미론에 의존한다. |

---

## 1. 오류 계약 — RFC 9457 Problem Details

모든 오류: `Content-Type: application/problem+json`, 본문 `{type, title, status, detail, instance}` + 유형별 확장 필드.
- `type` = `urn:problem:order-payment:{slug}` (결정: 안정적 식별자로 URN 사용. 근거: 해석 가능한 문서 URL이 없고 slug로 분기하기 충분).
- `instance` = 요청 경로(`/api/orders/12/pay`). `title`은 slug별 고정 영문 문구, `detail`은 사람이 읽는 설명(내부 정보·스택 비노출).
- 구현: `@RestControllerAdvice` + `ResponseEntityExceptionHandler` 확장, `spring.mvc.problemdetails.enabled=true`, `spring.web.resources.add-mappings=false`(미지 경로가 problem+json 404가 되도록).

### 1.1 유형 × 상태코드 매핑

| slug | HTTP | 발생 조건 | 확장 필드 |
|---|---|---|---|
| `validation-failed` | 400 | Bean Validation 실패(본문·쿼리 파라미터·헤더 형식): name 공백, price/stock<0, RATE 범위, validFrom>=validUntil, items 비어있음, quantity<1, productId 중복, size 범위 밖, 알 수 없는 status 쿼리 값, 빈 userId 등 | `errors: [{field, message}]` |
| `missing-header` | 400 | `X-User-Id`(주문 생성), `Idempotency-Key`(주문 생성·결제) 누락 | `header` |
| `malformed-request` | 400 | JSON 파싱 오류, 타입 불일치(`price:"abc"`, 소수), 알 수 없는 enum 본문 값(`type:"FOO"`), 경로 변수 타입 오류(`/api/orders/abc`), 본문 자체 누락 | — |
| `invalid-cursor` | 400 | `cursor` 디코딩/형식 검증 실패 | — |
| `product-not-found` | 404 | 상품 id 없음(GET, 주문 항목) | `productId` |
| `coupon-not-found` | 404 | 쿠폰 code 없음(GET, 주문 couponCode) | `couponCode` |
| `order-not-found` | 404 | 주문 id 없음 | `orderId` |
| `resource-not-found` | 404 | 매핑되지 않은 경로 | — |
| `method-not-allowed` | 405 | 허용되지 않은 메서드 | — |
| `unsupported-media-type` | 415 | Content-Type 불일치 | — |
| `coupon-code-duplicate` | 409 | 쿠폰 code UNIQUE 위반(사전 조회 + 제약명 `uk_coupons_code` 위반 변환으로 경쟁 처리) | `couponCode` |
| `insufficient-stock` | 409 | 주문 시 `available < quantity` (주문·예약 전체 롤백) | `productId`, `requested`, `available` |
| `invalid-order-state` | 409 | 상태 전이 표(§3.3) 위반 — pay/cancel/ship/deliver | `currentStatus`, `action` |
| `order-expired` | 409 | pay/cancel 호출 시 주문이 EXPIRED이거나 TTL 경과로 지금 만료 처리됨 | `expiresAt` |
| `idempotency-key-conflict` | 409 | 동일 (user, key)에 다른 본문(주문 생성) / 주문에 이미 다른 결제 키가 묶여 있음 또는 같은 키·다른 cardToken(결제) | — |
| `operation-in-progress` | 409 | 같은 주문에 대해 결제/환불이 진행 중(리스 활성)이라 이 요청을 처리할 수 없음 (+ 헤더 `Retry-After: 1`) | `operation: PAY\|REFUND` |
| `coupon-not-in-period` | 422 | `now < validFrom` 또는 `now > validUntil` | `couponCode` |
| `coupon-min-order-not-met` | 422 | `subtotal < minOrderAmount` | `couponCode`, `minOrderAmount`, `subtotal` |
| `coupon-exhausted` | 422 | `usedCount >= totalQuantity` | `couponCode` |
| `pg-gateway-error` | 502 | PG 5xx, 연결 실패(connection refused 등), PG 4xx, 응답 본문 형식/status 값 이상 | `orderId`, `retryable: true` |
| `pg-gateway-timeout` | 504 | PG 연결/읽기 타임아웃 | `orderId`, `retryable: true` |
| `internal-error` | 500 | 그 외 모든 예외. `detail`은 고정 문구("Unexpected error"), 원인은 서버 로그에만 | — |

### 1.2 409 vs 422 확정

- 결정: **409** = 대상 리소스(주문·재고·쿠폰 code)의 현재 상태/동시성과 충돌(상태 전이 위반, 재고 부족, 중복 code, 멱등키 충돌, 진행 중 작업). **422** = 문법·존재는 정상이나 **쿠폰 사용 규칙(기간·최소금액·소진)** 이라는 비즈니스 규칙 위반.
- 결정: 쿠폰 소진도 422(`coupon-exhausted`). 근거: R06이 기간·최소금액·소진을 한 묶음("사용 불가 쿠폰")으로 서술, 클라이언트는 type slug로 구분 가능.
- 결정: 멱등키 불일치는 **409**(`idempotency-key-conflict`). 근거: R07 허용 범위(409/422) 중 요청이 주문의 기존 상태(이미 키가 소비됨)와 충돌하는 의미.
- 주문 생성의 검사 우선순위: 400(헤더/본문) → 404(상품, 쿠폰) → 422(쿠폰 규칙 사전 검사) → 409(재고 부족) → 422(동시 소진 경쟁에서 조건부 UPDATE 실패 시 `coupon-exhausted`).

---

## 2. 엔드포인트

### 2.1 POST /api/products — 상품 등록 (R01)

요청 본문

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `name` | string | Y | 공백 불가(trim 후 1~255자). 저장은 trim된 값 |
| `price` | long | Y | 0 ≤ price ≤ 1,000,000,000 |
| `stock` | int | Y | 0 ≤ stock ≤ 1,000,000,000 |

- 201 Created, `Location: /api/products/{id}`, 본문 = Product(§2.2). 초기 `reserved=0`, `available=stock`.
- 오류: 400 `validation-failed` / `malformed-request`, 415.

### 2.2 GET /api/products/{id} — 상품 조회 (R02)

200 본문 Product

| 필드 | 타입 | nullable | 설명 |
|---|---|---|---|
| `id` | long | N | |
| `name` | string | N | |
| `price` | long | N | 원 |
| `stock` | int | N | 물리 재고(ship 확정 시 차감) |
| `reserved` | int | N | 예약(주문 생성~ship/해제) |
| `available` | int | N | `stock - reserved`(응답 시 계산, DB 컬럼 아님) |

오류: 404 `product-not-found`, 400 `malformed-request`(id 타입 오류).

### 2.3 POST /api/coupons — 쿠폰 등록 (R03)

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `code` | string | Y | `^[A-Za-z0-9_-]{1,64}$`(경로 안전). 대소문자 구분 |
| `type` | enum `FIXED`\|`RATE` | Y | 그 외 값 → 400 `malformed-request` |
| `value` | long | Y | FIXED: 1 ≤ value ≤ 1,000,000,000 / RATE: 1 ≤ value ≤ 100 (정수 %) |
| `minOrderAmount` | long | N(기본 0) | ≥ 0 (null/누락 → 0으로 저장·응답) |
| `maxDiscountAmount` | long | N(nullable) | ≥ 0. 결정: **null 또는 0 = 상한 없음**. 근거: 상한 0은 무의미하므로 관례적 "무제한" 취급(모호 사안, 입력값은 그대로 저장·반환) |
| `totalQuantity` | int | Y | 1 ≤ totalQuantity ≤ 1,000,000,000 |
| `validFrom` | instant | Y | `validFrom < validUntil` |
| `validUntil` | instant | Y | |

- 201 Created, `Location: /api/coupons/{code}`, 본문 = Coupon(§2.4), `usedCount=0`.
- 오류: 400 `validation-failed`(RATE 범위, 기간 역전, 음수 등)/`malformed-request`(type 값), 409 `coupon-code-duplicate`.

### 2.4 GET /api/coupons/{code} — 쿠폰 조회 (R04)

200 본문 Coupon: `code`(string), `type`(string), `value`(long), `minOrderAmount`(long, N), `maxDiscountAmount`(long, **nullable**), `totalQuantity`(int), `usedCount`(int), `validFrom`(instant), `validUntil`(instant).
오류: 404 `coupon-not-found`.

### 2.5 POST /api/orders — 주문 생성 + 재고 예약 (R05, R06, R07)

헤더: `X-User-Id`(필수), `Idempotency-Key`(필수).

| 본문 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `items` | array | Y | 1~100개. **`productId` 중복 금지**(결정: 병합하지 않고 400. 근거: 응답 `items` 의미 단순·락 순서 결정성) |
| `items[].productId` | long | Y | 존재해야 함(없으면 404) |
| `items[].quantity` | int | Y | 1 ≤ quantity ≤ 10,000 |
| `couponCode` | string | N(nullable) | null/누락 = 쿠폰 미사용. 빈 문자열은 400 |

- **201 Created**, `Location: /api/orders/{id}`, 본문 = Order(§2.6), `status=PENDING_PAYMENT`, `expiresAt = createdAt + ORDER_PAYMENT_TTL`.
- 가격: `unitPrice`=주문 시점 `products.price` 스냅샷. `subtotal = Σ(unitPrice × quantity)`. 상품 가격 변경 API가 없으므로 사전 조회 가격과 예약 시점 가격이 같다(변경 API가 추가되면 재검토 필요).
- 멱등 재생(동일 user+key+동일 본문 해시): 결정: **201 + 동일 Location + 최초 주문 본문**(현재 상태 반영 아님이 아니라 **현재 주문 상태 그대로**; 상태가 이미 바뀌었으면 바뀐 상태로 반환) + `Idempotent-Replayed: true`. 재고·쿠폰 추가 차감 없음. 근거: R07 "최초 주문을 그대로 반환", 원 응답 상태코드 재생이 IETF Idempotency-Key 관례.
- 같은 key + 다른 본문 해시 → 409 `idempotency-key-conflict`.
- **실패한 요청은 키를 소비하지 않는다**(404/409/422/400로 롤백되면 주문 행이 없으므로 같은 키로 재시도 가능).
- 오류: 400 `missing-header`/`validation-failed`/`malformed-request`, 404 `product-not-found`/`coupon-not-found`, 409 `insufficient-stock`/`idempotency-key-conflict`, 422 `coupon-*`.
- 요청 본문 해시: SHA-256 hex(소문자) of 정규화 문자열 `items=<productId>:<quantity>,…(productId 오름차순);coupon=<couponCode 또는 빈 문자열>`. 항목 순서만 다른 요청은 동일 요청으로 본다. 해시 범위에 `X-User-Id`는 포함하지 않는다(범위가 이미 user 단위).

### 2.6 Order 표현 (R08 및 모든 주문 응답 공통)

| 필드 | 타입 | nullable | 설명 |
|---|---|---|---|
| `id` | long | N | |
| `userId` | string | N | |
| `status` | enum string | N | `PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED, SHIPPED, DELIVERED` |
| `items` | array | N | 요청 항목 순서(`line_no`) 유지 |
| `items[].productId` | long | N | |
| `items[].quantity` | int | N | |
| `items[].unitPrice` | long | N | 주문 시점 스냅샷 |
| `couponCode` | string | **Y** | 미사용 시 null |
| `subtotal` | long | N | |
| `discount` | long | N | 쿠폰 미사용 시 0 |
| `totalPrice` | long | N | `subtotal - discount` |
| `createdAt` | instant | N | |
| `expiresAt` | instant | N | |
| `paidAt` | instant | **Y** | 결제 승인 시각, 이전엔 null(REFUNDED/SHIPPED/DELIVERED에서도 유지) |

`paymentId`, 리스/멱등 내부 컬럼은 응답에 노출하지 않는다.

### 2.7 GET /api/orders/{id} — 주문 단건 조회 (R08)

- 200 Order. 조회 시 **lazy 만료 수행**(§3.9): TTL 경과 + 결제 진행 중 아님이면 실제로 EXPIRED로 전이(재고·쿠폰 반환 포함)한 뒤 그 결과를 반환 → "조회 시점에 EXPIRED로 보임" 충족.
- 오류: 404 `order-not-found`, 400 `malformed-request`.

### 2.8 GET /api/orders — 목록 (R09)

| 쿼리 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `userId` | string | N | 정확 일치 필터. 빈 문자열 400 |
| `status` | enum | N | 8개 상태 이름(대소문자 구분). 그 외 400 `validation-failed` |
| `size` | int | N | 기본 **20**, 최소 1, 최대 **100**. 범위 밖/비숫자 400 |
| `cursor` | string | N | 이전 응답의 `nextCursor` |

- 200 `{ "content": [Order…], "nextCursor": string|null }`. `content`는 항상 배열(빈 결과 `[]`). 마지막 페이지/결과 없음이면 `nextCursor=null`.
- 정렬: **`id` 내림차순**(= 생성 역순; id는 단조 증가). 결정: 정렬 키를 `id` 단일로 한다. 근거: 유일·안정·인덱스 친화(`created_at` 동률 문제 없음).
- 커서: 결정: 불투명 토큰 = `Base64URL-no-padding("v1:" + lastId)`. 서버는 `^v1:[1-9][0-9]{0,18}$` 형식·Long 범위·URL-safe base64를 엄격 검증, 실패 시 400 `invalid-cursor`. 다음 페이지 = `WHERE id < lastId`. 필터는 커서에 인코딩하지 않으며 클라이언트가 같은 필터로 이어서 요청한다.
- `nextCursor` 생성: `size+1`건을 조회해 초과분이 있으면 반환 마지막 항목의 id로 인코딩, 없으면 null.
- 목록 호출 전 **범위 한정 만료 스윕**(§3.9)을 수행해 만료 대상 주문이 `PENDING_PAYMENT`로 노출되지 않게 한다.
- 구현 주의: 주문 N건의 items는 N+1 금지 — id 목록으로 `order_items IN (…)` 일괄 조회 후 조립.

### 2.9 POST /api/orders/{id}/pay — 결제 (R10, R11)

헤더 `Idempotency-Key`(필수). 본문 `{ "cardToken": string }` — 필수, 공백 불가, 1~255자.

| 결과 | HTTP | 본문 |
|---|---|---|
| PG `APPROVED` | **200** | Order(`status=PAID`, `paidAt` 채움) |
| PG `DECLINED` | **200** | Order(`status=PAYMENT_FAILED`). 결정: 거절은 요청 처리 성공이므로 4xx 아님, 결과는 본문 status로 표현 |
| 같은 키 재요청(결과 확정 후) | 200 | 저장된 결과 기준 현재 Order, PG 재호출 없음, `Idempotent-Replayed: true` |
| PG 타임아웃 | 504 `pg-gateway-timeout` | 주문은 PENDING_PAYMENT 유지 |
| PG 5xx/연결실패/이상 응답 | 502 `pg-gateway-error` | 주문은 PENDING_PAYMENT 유지 |
| 상태 위반 | 409 `invalid-order-state` / `order-expired` | |
| 키·카드 충돌 | 409 `idempotency-key-conflict` | |
| 진행 중 | 409 `operation-in-progress` | |
| 입력 오류 | 400 `missing-header`/`validation-failed`/`malformed-request` | |
| 주문 없음 | 404 `order-not-found` | |

- PG 호출 본문: `{orderId(number), amount(=totalPrice, number), cardToken}`, 헤더 `Idempotency-Key` = **클라이언트가 보낸 키를 그대로 전달**. 결정: 변환 없이 전달. 근거: PG 측 멱등성이 클라이언트 재시도 키와 정확히 대응, 스텁 검증 용이.
- 상세 알고리즘은 §3.8.

### 2.10 POST /api/orders/{id}/cancel — 취소/환불 (R13)

본문 없음. 200 Order.
- `PENDING_PAYMENT` → `CANCELLED`(PG 호출 없음).
- `PAID` → PG 환불 호출 성공 시 `REFUNDED`.
- 환불 PG 실패: 502/504 problem, 주문은 PAID 유지(재시도 가능).
- 그 외 상태 409 `invalid-order-state`(EXPIRED·TTL 경과 직후는 `order-expired`). 중복 취소(이미 CANCELLED/REFUNDED)는 **재생이 아니라 409**.

### 2.11 POST /api/orders/{id}/ship, /deliver (R14)

본문 없음. 200 Order. `ship`: PAID→SHIPPED, `deliver`: SHIPPED→DELIVERED. 그 외 409 `invalid-order-state`. 없는 주문 404. 결제/환불 진행 중이면 409 `operation-in-progress`.

---

## 3. 도메인 규칙

### 3.1 재고 모델 (R05, R12, R13, R14, A1, A3)

`stock`=물리 재고, `reserved`=예약분, `available = stock - reserved`(계산). 불변식: `0 ≤ reserved ≤ stock` (DB CHECK).

| 이벤트 | stock | reserved | available | 쿠폰 usedCount |
|---|---|---|---|---|
| 주문 생성(PENDING_PAYMENT) | 불변 | += qty | −qty | +1 |
| 결제 성공(→PAID) | 불변 | 불변 | 불변 | 불변 |
| 결제 거절(→PAYMENT_FAILED) | 불변 | **−= qty** | +qty | **−1** |
| 취소(PENDING→CANCELLED) | 불변 | −= qty | +qty | −1 |
| 만료(PENDING→EXPIRED) | 불변 | −= qty | +qty | −1 |
| 환불(PAID→REFUNDED) | 불변 | −= qty | +qty | −1 |
| ship(PAID→SHIPPED) | **−= qty** | **−= qty** | 불변 | 불변(사용 확정) |
| deliver | 불변 | 불변 | 불변 | 불변 |
| PG 장애(502/504) | 불변 | 불변 | 불변 | 불변 |

결정:
- ship은 **stock과 reserved를 한 문장으로 동시에 차감**(A1 확정). 근거: available이 ship 전후 변하지 않아야 이중 계산 없음.
- **PAYMENT_FAILED는 종결 상태**(R15에 나가는 전이 없음)이므로 거절 시점에 재고·쿠폰을 반환한다. 반환하지 않으면 영구 누수.
- **PAYMENT_FAILED 이후 재결제 불허**(A3 확정): pay는 409 `invalid-order-state`. 사용자는 새 Idempotency-Key로 새 주문을 만든다. 근거: R15 전이 표 준수 + 거절 시 이미 예약을 반환했으므로 재결제는 재예약이 필요해 모델이 복잡해짐.
- 각 해제(거절·취소·만료·환불)는 **주문 상태 조건부 UPDATE(claim)가 1행 성공한 트랜잭션에서만, 같은 트랜잭션 안에서** 수행 → 정확히 1회(§3.7).

### 3.2 쿠폰 모델 (R06)

검증(순서): 존재(404) → 기간 `validFrom ≤ now ≤ validUntil`(422 `coupon-not-in-period`) → `subtotal ≥ minOrderAmount`(422 `coupon-min-order-not-met`) → 잔여 `usedCount < totalQuantity`(422 `coupon-exhausted`).

할인 계산(정수 연산, `long`):
1. `raw = FIXED ? value : floor(subtotal × value / 100)` — RATE 반올림 규칙: **내림(정수 나눗셈)**. `subtotal×value` 최대 ≈ 10^15×100 = 10^17 < Long.MAX 로 오버플로 없음(items ≤100, quantity ≤10,000, price ≤10^9).
2. `maxDiscountAmount`가 null이 아니고 > 0이면 `raw = min(raw, maxDiscountAmount)`(**FIXED/RATE 모두 적용**).
3. `discount = min(raw, subtotal)` (subtotal 초과 불가).
4. `totalPrice = subtotal − discount` (≥ 0).

usedCount 증감:
- +1: 주문 생성 트랜잭션 안에서 조건부 UPDATE로(§3.7). 
- −1: 거절/취소/만료/환불 해제 시(claim 성공 트랜잭션에서만, `used_count > 0` 가드).
- ship/deliver는 변경 없음.
- 사용자당 사용 횟수 제한은 요구 없음 → 결정: 제한하지 않음(같은 사용자가 여러 주문에 같은 쿠폰 사용 가능, 총량만 제한).
- 쿠폰 기간은 **주문 생성 시점**에만 검증(결제 시점 재검증 없음). 근거: 사용 수량이 주문 생성 시점에 예약되는 모델.

### 3.3 상태 전이 표 (R15)

전이 규칙은 도메인 한 곳(`OrderStatus` enum의 허용 전이 맵 + `OrderStateMachine`/도메인 서비스)에만 둔다. 모든 상태 변경은 §3.7의 **상태 조건부 UPDATE**로 수행.

| 전이 | 트리거 | 추가 가드 | 부수효과 |
|---|---|---|---|
| (신규)→PENDING_PAYMENT | POST /orders | 재고·쿠폰 예약 성공 | reserved+=qty, used_count+1, `expiresAt=createdAt+TTL` |
| PENDING_PAYMENT→PAID | pay + PG APPROVED | 리스 보유 | paid_at 기록, payments.status=APPROVED |
| PENDING_PAYMENT→PAYMENT_FAILED | pay + PG DECLINED | 리스 보유 | reserved−=qty, used_count−1, payments.status=DECLINED |
| PENDING_PAYMENT→EXPIRED | 스케줄러/lazy | `expires_at ≤ now`, 리스 없음 | reserved−=qty, used_count−1 |
| PENDING_PAYMENT→CANCELLED | cancel | `expires_at > now`, 리스 없음 | reserved−=qty, used_count−1 |
| PAID→SHIPPED | ship | 리스 없음 | stock−=qty, reserved−=qty, shipped_at |
| PAID→REFUNDED | cancel + PG refund 성공 | 리스 보유 | reserved−=qty, used_count−1, payments.status=REFUNDED |
| SHIPPED→DELIVERED | deliver | 리스 없음 | delivered_at |

현재 상태 × 요청 결과 매트릭스(성공=200, 그 외 409 사유):

| 현재 상태 | pay | cancel | ship | deliver |
|---|---|---|---|---|
| PENDING_PAYMENT | 200 (만료 경과 시 `order-expired`) | 200 (만료 경과 시 `order-expired`) | invalid-order-state | invalid-order-state |
| PAID | invalid-order-state (같은 키면 재생 200) | 200(환불) | 200 | invalid-order-state |
| PAYMENT_FAILED | invalid-order-state (같은 키면 재생 200) | invalid-order-state | invalid-order-state | invalid-order-state |
| EXPIRED | order-expired | order-expired | invalid-order-state | invalid-order-state |
| CANCELLED / REFUNDED | invalid-order-state | invalid-order-state | invalid-order-state | invalid-order-state |
| SHIPPED | invalid-order-state | invalid-order-state | invalid-order-state | 200 |
| DELIVERED | invalid-order-state | invalid-order-state | invalid-order-state | invalid-order-state |

- 전이 위반 응답: 409 `invalid-order-state`, `currentStatus`/`action` 확장 필드.
- 결제·환불 진행 중(리스 활성)인 주문에 대한 상충 요청은 409 `operation-in-progress`(`Retry-After: 1`).

### 3.4 멱등성 — 주문 생성 (R07)

- 범위: `(user_id, idempotency_key)` UNIQUE (`uk_orders_user_idem`). `orders.request_hash`(SHA-256 hex, §2.5) 저장.
- 흐름: ① 빠른 조회(user,key) → 존재하면 해시 비교: 같으면 재생 201, 다르면 409. ② 없으면 트랜잭션에서 `INSERT … ON CONFLICT (user_id, idempotency_key) DO NOTHING` → 0행이면(동시 요청이 먼저 커밋; 충돌 시 상대 커밋까지 블록됨) 트랜잭션을 롤백하고 ①로 돌아가 재생/충돌 판정. 
- 동시 동일 키: unique 인덱스가 직렬화하고 주문은 1건만 생성, 나머지는 재생 응답.
- 롤백(재고 부족 등)되면 주문 행이 없으므로 키는 소비되지 않는다.

### 3.5 멱등성 — 결제 (R10)

- 저장: `payments` 테이블(주문당 1행, `UNIQUE(order_id)`): `idempotency_key`, `request_hash`(= SHA-256 hex of cardToken; **cardToken 원문은 저장·로그 금지**), `status`, `pg_payment_id`, `attempt_count`, `last_error`.
- 결정: **한 주문에는 결제 키가 하나만 묶인다(첫 pay 요청의 키)**. 근거: PAYMENT_FAILED/PAID가 종결이므로 한 주문 = 최대 한 번의 PG 결제 의미론. 게이트웨이 오류(불확정 결과) 후 다른 키로 재시도하면 PG가 같은 결제를 이중 승인할 수 있으므로 금지.
- 같은 키·같은 cardToken 재요청:
  - 결과 확정(APPROVED/DECLINED/REFUNDED) → PG 재호출 없이 200 재생.
  - 미확정(INITIATED: 게이트웨이 오류 또는 진행 중) → 진행 중이면 409 `operation-in-progress`, 아니면 **같은 키로 PG 재호출**(PG 멱등).
- 같은 키·다른 cardToken → 409 `idempotency-key-conflict`. 다른 키: 주문이 아직 PENDING이고 결제 행이 있으면 409 `idempotency-key-conflict`, 주문이 이미 PENDING이 아니면 409 `invalid-order-state`.
- PG 호출에는 클라이언트 키를 그대로 전달(§2.9).
- 게이트웨이 오류 후 사용자가 카드를 바꾸고 싶은 경우: 지원하지 않음(같은 키·같은 카드로만 재시도). 주문은 TTL 후 만료된다. [Q.] 비차단 — 카드 변경 재시도가 필요하면 별도 확인.

### 3.6 상태 경쟁 방지 — 리스(lease) + 조건부 UPDATE (R15, R19)

PG 호출 동안 DB 트랜잭션·행 잠금을 잡지 않으면서(R11) pay vs cancel vs expire 경쟁을 막기 위해 **주문 행의 짧은 리스**를 쓴다.
- `orders.lease_kind(PAY|REFUND)`, `lease_token(UUID)`, `lease_expires_at`. 리스가 "활성" = `lease_token IS NOT NULL AND lease_expires_at > now`.
- 모든 상태 전이 UPDATE는 `WHERE id=? AND status=<from> AND (리스 없음 또는 만료)`를 포함. 결제/환불 완료 UPDATE만 `lease_token = <내 토큰>`을 조건으로 한다.
- 결과 판정: 영향 행 수 1 = 전이 성공(부수효과 수행), 0 = 재조회 후 `operation-in-progress` / `order-expired` / `invalid-order-state` 중 하나로 매핑.
- 리스 기간 `order-payment.lease-duration`(기본 PT30S)은 PG `connect+read` 타임아웃 합의 2배 이상이어야 한다(기동 검증). 프로세스 크래시로 남은 리스는 자연 만료되어 주문이 영구 잠기지 않는다.
- 결정: `@Version` 대신 상태·리스 조건부 UPDATE 사용. 근거: 해제(반환)·부수효과를 "claim 성공 1행"에 묶어 정확히 1회를 증명하기 쉽고, 낙관적 락 재시도 루프가 필요 없다. `orders.version`은 감사용으로 모든 UPDATE에서 `version = version + 1`.

### 3.7 동시성 전략 (R05, R06, R19)

세부 SQL은 `02_db_design.md` §3. 요지:
- **재고 예약**: `UPDATE products SET reserved = reserved + :q WHERE id=:id AND stock - reserved >= :q` → 0행이면 409 `insufficient-stock`. SELECT 후 UPDATE 금지. CHECK `reserved<=stock`이 최종 방어선.
- **쿠폰 사용**: `UPDATE coupons SET used_count = used_count + 1 WHERE id=:id AND used_count < total_quantity AND valid_from <= :now AND valid_until >= :now` → 0행이면 사유 재조회 후 422.
- **락 순서(데드락 회피)**: 전역 순서 `orders 행 → products(id 오름차순) → coupons`. 
  - 주문 생성: ① orders INSERT(신규 행, 대기 대상 없음) → ② products 예약을 id 오름차순 → ③ coupons → ④ order_items INSERT. 
  - 해제 경로(거절/취소/만료/환불): ① orders claim UPDATE → ② products id 오름차순 → ③ coupons.
  - ship: ① orders claim → ② products id 오름차순.
- 상태 경쟁: §3.6. pay vs cancel vs expire vs ship 중 claim UPDATE를 이기는 쪽 하나만 성공, 나머지는 재평가에서 0행 → 409. 따라서 최종 상태 단일, 반환/환불 정확히 1회.

### 3.8 PG 연동 흐름 (R10, R11, R13)

클라이언트: Spring `RestClient`(JDK 또는 Simple request factory), base URL=`order-payment.gateway.url`, `connect-timeout` 기본 PT2S, `read-timeout` 기본 PT5S. 요청 내부 자동 재시도 없음(재시도는 클라이언트가 같은 키로).
오류 매핑: 타임아웃(연결·읽기) → 504; 연결 거부/5xx/4xx/JSON 이상/status 값이 APPROVED·DECLINED(환불은 REFUNDED)가 아님 → 502.

**결제 (3단계, PG 호출은 트랜잭션 밖)**
1. TX-A(짧음): 주문·결제행 조회 → 재생/충돌/상태 판정(§3.3, §3.5) → 만료 경과면 expire 처리 후 409 `order-expired` → 리스 claim(`lease_kind=PAY`, 새 토큰, `expires_at > now` 조건 포함) → `payments` UPSERT(최초 INSERT, 재시도는 `attempt_count+1`, `status=INITIATED`) → 커밋.
2. PG 호출(트랜잭션·행 잠금 없음): `POST /v1/payments`.
3. TX-C(짧음):
   - APPROVED: `UPDATE orders SET status='PAID', paid_at=:now, 리스 해제 WHERE id AND status='PENDING_PAYMENT' AND lease_token=:t` + `payments.status=APPROVED, pg_payment_id`.
   - DECLINED: `status='PAYMENT_FAILED'` 동일 조건 + 재고·쿠폰 해제 + `payments.status=DECLINED`.
   - 502/504: 리스만 해제(`lease_token=NULL`), `payments.last_error` 기록, 주문 PENDING_PAYMENT 유지 → 같은 키로 재시도 가능. 그 후 problem 응답.
   - 완료 UPDATE가 0행(리스 상실 — 리스 기간 > PG 타임아웃이므로 정상 운영에서는 불가)이면 ERROR 로그 + 409 `operation-in-progress`.
- 결제 승인 이후 `expiresAt`이 지나도 승인은 유효하다: 리스를 먼저 잡은 쪽이 이기며(claim 시점 `expires_at > now`), 스윕/lazy 만료는 활성 리스를 건너뛴다.

**환불 (cancel on PAID, 동일 3단계)**
1. TX-A: 주문 `PAID` 확인 → 리스 claim(`REFUND`) → 커밋.
2. PG `POST /v1/payments/{pgPaymentId}/refund` (추가로 `Idempotency-Key: refund-{orderId}` 헤더를 전달 — PG 계약 외 보조 헤더, 스텁은 무시 가능).
3. TX-C: 성공 → `status='REFUNDED'` + reserved 반환 + 쿠폰 반환 + `payments.status=REFUNDED` + 리스 해제. 502/504 → 리스만 해제, 주문 PAID·결제 APPROVED 유지, 502/504 problem. 
- 환불 실패 후 재시도: cancel 재호출 시 다시 환불 시도(PG 멱등 전제). 중복 cancel은 리스/상태 조건부 UPDATE로 이중 환불·이중 반환 없음.

**paymentId 저장 위치**: 결정: `payments.pg_payment_id`(주문 1:1). `orders`에는 컬럼을 두지 않는다. 근거: 중복 저장은 불일치 위험이며, 환불은 `payments`를 주문 id로 조회하면 충분. 주문 응답에는 노출하지 않음.

**알려진 한계 [Q.] (비차단, 문서화로 종결)**
- 게이트웨이 오류(불확정) 후 사용자가 재시도하지 않고 cancel하거나 TTL로 만료되면, PG에서 실제로는 승인되었을 수 있다. 정산 대사(reconciliation)는 범위 밖. 
- `totalPrice=0` 주문(가격 0 또는 전액 할인)도 일관성을 위해 `amount=0`으로 PG를 호출한다. PG가 0원을 거절하면 DECLINED 또는 502 경로를 탄다.

### 3.9 만료 설계 (R08, R12)

- `expiresAt = createdAt + ORDER_PAYMENT_TTL` (생성 시 계산해 컬럼에 저장; 이후 TTL 설정을 바꿔도 기존 주문 불변).
- 만료 처리 단위 `expireIfDue(orderId, now)` = 단일 트랜잭션: `UPDATE orders SET status='EXPIRED' … WHERE id AND status='PENDING_PAYMENT' AND expires_at <= :now AND (리스 없음/만료)` → 1행이면 products(id 오름차순)·coupon 반환. 0행이면 아무 것도 하지 않음 → **여러 인스턴스/스케줄러/lazy가 겹쳐도 해제 1회**(claim이 곧 멱등성).
- 역할 분담:
  - **스케줄러**(주 경로): `@Scheduled(fixedDelayString="${order-payment.expiry.sweep-interval}")` 기본 PT10S, 배치 100: `SELECT id FROM orders WHERE status='PENDING_PAYMENT' AND expires_at <= :now ORDER BY expires_at LIMIT :batch` → 각 id를 별도 트랜잭션으로 `expireIfDue`. `order-payment.expiry.sweep-enabled`(기본 true)로 테스트에서 끌 수 있다. `@EnableScheduling` 필요.
  - **lazy**(정확성 경로): GET 단건(§2.7), pay·cancel(§3.3 — 만료 경과면 `expireIfDue` 후 409 `order-expired`), GET 목록(요청 범위 userId/status 필터에 해당하는 만료 대상을 최대 500건 즉시 처리). ship/deliver는 PAID/SHIPPED만 대상이라 불필요.
- 결제 진행 중(리스 활성) 주문은 만료 경과여도 건너뛴다(결제가 이김).

### 3.10 설정 키 (R17)

| 프로퍼티 | 환경 변수 | 기본값 | 검증 |
|---|---|---|---|
| `spring.datasource.url/username/password` | `SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD` | 없음(필수) | Boot relaxed binding 자동 매핑 — **application.yml에 `${…}` 명시하지 않는다**(테스트의 `@ServiceConnection`/`DynamicPropertySource`와 충돌 방지) |
| `server.port` | `SERVER_PORT` | 8080 | `server.port: ${SERVER_PORT:8080}` |
| `order-payment.gateway.url` | `PAYMENT_GATEWAY_URL` | `http://localhost:8081` | 절대 http(s) URI. 결정: 요구상 "필수"이나 컨텍스트 로딩 테스트가 값 없이 깨지지 않도록 개발용 기본값 부여, 운영은 환경 변수로 반드시 지정 |
| `order-payment.ttl` | `ORDER_PAYMENT_TTL` | `PT15M` | `Duration`(ISO-8601), > 0. 형식 오류/0/음수는 `@Validated @ConfigurationProperties` 바인딩 실패 → **기동 실패** |
| `order-payment.gateway.connect-timeout` | — | PT2S | > 0 |
| `order-payment.gateway.read-timeout` | — | PT5S | > 0 |
| `order-payment.lease-duration` | — | PT30S | ≥ 2×(connect+read) |
| `order-payment.expiry.sweep-enabled` | — | true | |
| `order-payment.expiry.sweep-interval` | — | PT10S | > 0 |
| `order-payment.expiry.batch-size` | — | 100 | 1~1000 |

고정 yml 설정: `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`, `spring.flyway.enabled=true`(locations 기본 `classpath:db/migration`), `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`, `spring.jackson.deserialization.accept-float-as-int=false`, `spring.mvc.problemdetails.enabled=true`, `spring.web.resources.add-mappings=false`. `application.yml`의 `spring.application.name: order-service`는 유지.

### 3.11 테스트 보조 설계 메모 (A4)

- PG는 스텁 서버로 대체: 기존 의존성에 HTTP 스텁 도구가 없다. 새 의존성은 불필요 — JDK `com.sun.net.httpserver.HttpServer`(또는 `MockRestServiceServer`는 RestClient 빌더 바인딩 시)로 충분. WireMock 등을 추가한다면 `build.gradle` 변경 사유를 구현 단계에서 기록(A4).
- 동시성 테스트(R19): Testcontainers PostgreSQL + `ExecutorService`/`CountDownLatch`로 N 동시 주문(oversell 0, 쿠폰 초과 0), 동시 pay/cancel/expire(최종 상태 단일, `reserved`/`usedCount` 1회 반환, 환불 호출 1회).
- 만료 테스트는 `Clock` 빈을 교체하거나 `order-payment.ttl=PT1S`와 sweep 비활성 + lazy 호출로 검증.

---

## 정합 요약

### A. 응답 필드 ↔ DB 컬럼 대응표

Product (`products`)

| 응답 필드 | 컬럼 | 타입(DB→JSON) | 비고 |
|---|---|---|---|
| id | products.id | BIGINT→number | |
| name | products.name | VARCHAR(255) NOT NULL→string | |
| price | products.price | BIGINT NOT NULL→number | CHECK ≥0 |
| stock | products.stock | INTEGER NOT NULL→number | CHECK ≥0 |
| reserved | products.reserved | INTEGER NOT NULL DEFAULT 0→number | CHECK 0≤reserved≤stock |
| available | (계산) stock − reserved | — | 컬럼 없음 |

Coupon (`coupons`)

| 응답 필드 | 컬럼 | 비고 |
|---|---|---|
| code | coupons.code VARCHAR(64) NOT NULL UNIQUE | `uk_coupons_code` |
| type | coupons.type VARCHAR(10) NOT NULL | CHECK IN('FIXED','RATE') |
| value | coupons.value BIGINT NOT NULL | CHECK >0, RATE ≤100 |
| minOrderAmount | coupons.min_order_amount BIGINT NOT NULL DEFAULT 0 | 요청 누락 시 0 |
| maxDiscountAmount | coupons.max_discount_amount BIGINT **NULL** | null/0=상한 없음 |
| totalQuantity | coupons.total_quantity INTEGER NOT NULL | CHECK ≥1 |
| usedCount | coupons.used_count INTEGER NOT NULL DEFAULT 0 | CHECK 0≤used≤total |
| validFrom / validUntil | coupons.valid_from / valid_until TIMESTAMPTZ NOT NULL | CHECK from<until |

Order (`orders`, `order_items`)

| 응답 필드 | 컬럼 | 비고 |
|---|---|---|
| id | orders.id BIGINT | |
| userId | orders.user_id VARCHAR(64) NOT NULL | |
| status | orders.status VARCHAR(20) NOT NULL | CHECK 8값 |
| items[].productId | order_items.product_id BIGINT NOT NULL FK | |
| items[].quantity | order_items.quantity INTEGER NOT NULL | CHECK >0 |
| items[].unitPrice | order_items.unit_price BIGINT NOT NULL | 스냅샷, CHECK ≥0 |
| (items 순서) | order_items.line_no INTEGER NOT NULL | 응답 정렬용, 비노출 |
| couponCode | orders.coupon_code VARCHAR(64) **NULL** | `coupon_id` FK와 쌍 |
| subtotal | orders.subtotal BIGINT NOT NULL | |
| discount | orders.discount BIGINT NOT NULL DEFAULT 0 | CHECK ≤subtotal |
| totalPrice | orders.total_price BIGINT NOT NULL | CHECK = subtotal − discount |
| createdAt | orders.created_at TIMESTAMPTZ NOT NULL | |
| expiresAt | orders.expires_at TIMESTAMPTZ NOT NULL | |
| paidAt | orders.paid_at TIMESTAMPTZ **NULL** | |
| (비노출) | orders.idempotency_key, request_hash, lease_*, version, shipped_at, delivered_at, updated_at | |
| (비노출) paymentId | payments.pg_payment_id | |

목록: `content[]`=Order, `nextCursor` = `orders.id` 마지막 항목의 Base64URL 인코딩(컬럼 아님).

### B. 제약/상태 위반 ↔ HTTP 상태(+problem type) 매핑

| 위반 원천 | 제약/조건 | HTTP | problem slug |
|---|---|---|---|
| 요청 검증 | name 공백, price/stock<0, RATE 1~100 밖, 기간 역전, quantity<1, items 비어있음, productId 중복, size 범위, status 값 | 400 | validation-failed |
| 헤더 | X-User-Id / Idempotency-Key 누락 | 400 | missing-header |
| 파싱/타입 | JSON 오류, 타입 불일치, 알 수 없는 type enum | 400 | malformed-request |
| 커서 | 형식 불량 | 400 | invalid-cursor |
| 조회 실패 | products/coupons/orders 행 없음 | 404 | product-/coupon-/order-not-found |
| UNIQUE | `uk_coupons_code` | 409 | coupon-code-duplicate |
| UNIQUE | `uk_orders_user_idem` + 해시 불일치 | 409 | idempotency-key-conflict |
| UNIQUE | `uk_orders_user_idem` + 해시 일치 | 201(재생) | — |
| UNIQUE | `uk_payments_order`(키 상이/카드 상이) | 409 | idempotency-key-conflict |
| CHECK(조건부 UPDATE 0행) | `ck_products_reserved` (stock−reserved<qty) | 409 | insufficient-stock |
| 조건부 UPDATE 0행 | coupons 기간 | 422 | coupon-not-in-period |
| 조건부 UPDATE 0행 | coupons `used_count < total_quantity` | 422 | coupon-exhausted |
| 업무 규칙 | subtotal < min_order_amount | 422 | coupon-min-order-not-met |
| 상태 조건 | orders.status 전이 위반 | 409 | invalid-order-state |
| 상태 조건 | EXPIRED / TTL 경과 (pay/cancel) | 409 | order-expired |
| 리스 조건 | 활성 리스(PAY/REFUND) | 409 | operation-in-progress |
| FK | order_items.product_id → products.id | 404 (사전 조회로 차단; 미차단 시 500 취급) | product-not-found |
| FK | orders.coupon_id → coupons.id | 404 (사전 조회로 차단) | coupon-not-found |
| 외부 | PG 타임아웃 | 504 | pg-gateway-timeout |
| 외부 | PG 5xx/연결 실패/4xx/이상 응답 | 502 | pg-gateway-error |
| 기타 CHECK/FK 위반 | 도달 불가 방어선(버그) | 500 | internal-error |

### C. 요구사항 id ↔ 설계 위치

| R | 설계 위치 |
|---|---|
| R01 | §2.1, §1.1(validation-failed), DB products |
| R02 | §2.2, §3.1 |
| R03 | §2.3, §1.1(coupon-code-duplicate), DB coupons CHECK/UNIQUE |
| R04 | §2.4 |
| R05 | §2.5, §3.1, §3.7(재고 예약·락 순서), DB order_items/orders |
| R06 | §3.2, §2.5, §1.2(422 확정), §3.7 |
| R07 | §3.4, §2.5, `uk_orders_user_idem` |
| R08 | §2.6, §2.7, §3.9(lazy 만료) |
| R09 | §2.8(커서·정렬·size), DB 인덱스 `idx_orders_*` |
| R10 | §2.9, §3.5, §3.8 |
| R11 | §3.8(트랜잭션 밖 호출·타임아웃·502/504), §3.10, §3.6(리스) |
| R12 | §3.9, §3.1, §3.3 |
| R13 | §2.10, §3.8(환불), §3.3, §3.1 |
| R14 | §2.11, §3.1(ship 재고 확정), §3.3 |
| R15 | §3.3, §3.6, §3.7 |
| R16 | §1(전체), §1.2 |
| R17 | §3.10 |
| R18 | `02_db_design.md` (DDL·Flyway 계획·인덱스·CHECK) |
| R19 | §3.6, §3.7, §3.11 |
| A1 | §3.1 | 
| A2 | §0(사용자 식별), §2.8 |
| A3 | §3.1(재결제 불허·거절 시 반환·ship 확정), §3.8(PAID cancel 환불) |
| A4 | §3.11 |
