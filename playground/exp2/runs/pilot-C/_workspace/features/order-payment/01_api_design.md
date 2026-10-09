# 01. API 설계 — order-payment

- 스택: Java 21 / Spring Boot 3.5.16 (Spring MVC 6.2) / Spring Data JPA + Hibernate 6 / PostgreSQL / Flyway / JUnit 5 + Testcontainers
- 기본 패키지: `com.example.order` (기존 `OrderApplication` 위치). 하위 패키지 권장: `common`(error·time·config), `product`, `coupon`, `order`, `payment`(PG 클라이언트), `idempotency`
- DB 설계: [02_db_design.md](./02_db_design.md)
- 원 요구: `feature.md` (R1~R11, C1~C4)

---

## 0. 공통 규칙

### 0.1 타입 규칙
| 구분 | Java | JSON | 근거 |
|---|---|---|---|
| 금액 (`price`, `unitPrice`, `subtotal`, `discount`, `totalPrice`, `value`, `minOrderAmount`, `maxDiscountAmount`, PG `amount`) | `long` / `Long` | number(정수) | C1 |
| 쿠폰 수량 (`totalQuantity`, `usedCount`) | `long` / `Long` | number | 상한 미정의 → int 오버플로 방지 |
| 재고·수량 (`stock`, `reserved`, `available`, `quantity`) | `int` / `Integer` | number | 상한 1,000,000 / 1,000 |
| id (`id`, `productId`) | `long` / `Long` | number | |
| 시각 | `OffsetDateTime` | string, ISO-8601 + 오프셋 | C2 |

- **요청 DTO 숫자 필드는 래퍼 타입**(`Long`, `Integer`) + `@NotNull`로 둔다 (누락 → 400 VALIDATION_ERROR).
- **시각 정규화(필수)**: 서버가 저장·응답하는 모든 시각은 `UTC(Z)`로 변환 후 **마이크로초로 truncate** 한다.
  - 서버 생성 시각: `OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)` (`Clock` 빈 = `Clock.systemUTC()`; 다른 곳에서 `now()` 직접 호출 금지).
  - 요청 입력 시각(`validFrom`, `validUntil`): `v.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)` 후 엔티티에 저장.
  - 이유: Postgres `TIMESTAMPTZ`는 마이크로초 정밀도. 엔티티 값 = DB 값이 되어야 POST 응답 = GET 응답, 커서 비교가 정확하다.
  - 결과: 응답 문자열은 항상 `...Z` 형태 (예: `2026-10-09T03:15:42.123456Z`, 초 단위 0 이하 소수부는 생략될 수 있음). 입력과 다른 오프셋이어도 **같은 순간**이다. 테스트는 문자열이 아니라 `OffsetDateTime.parse(...).toInstant()`로 비교한다.
- **null 직렬화**: nullable 응답 필드는 키를 생략하지 않고 `null`로 내보낸다 (Jackson 기본 inclusion 유지, `non_null` 전역 설정 금지).

### 0.2 Jackson 설정 (application.yml, 0.6 참조)
- `serialization.write-dates-as-timestamps: false`
- `deserialization.accept-float-as-int: false` — `"price": 1.5` → 400
- `mapper.allow-coercion-of-scalars: false` — `"price": "100"` → 400
- `FAIL_ON_UNKNOWN_PROPERTIES`는 Boot 기본(false) 유지 — 알 수 없는 필드는 무시
- 시각 문자열에 오프셋이 없으면(`2026-01-01T00:00:00`) 파싱 실패 → 400
- enum(`type`, `status`)은 **대소문자 구분 정확 일치**. 그 외 값 → 400

### 0.3 경로 변수 규칙
- `{id}`(상품·주문): `Long`으로 바인딩. **Long으로 변환 불가(`abc`, 오버플로 등) → 400 VALIDATION_ERROR** (`MethodArgumentTypeMismatchException`). 변환 가능하면(0·음수 포함) 조회 후 없으면 404.
  - 근거: 400은 C3에서 최우선이므로 결제 엔드포인트에서도 멱등 처리 이전에 확정된다(우선순위 모순 없음).
- `{code}`(쿠폰): 형식 검증 없음. 그대로 조회, 없으면 404 `COUPON_NOT_FOUND`.

### 0.4 Location 헤더
- **경로만 담은 상대 참조**: `/api/products/{id}`, `/api/coupons/{code}`, `/api/orders/{id}` (스킴·호스트 없음). 멱등 재생 시에도 동일 문자열을 돌려준다.

### 0.5 헤더 검증 규칙
| 헤더 | 적용 엔드포인트 | 규칙 | 위반 |
|---|---|---|---|
| `X-User-Id` | `POST /api/orders` (필수) | 존재, 공백 아님(`@NotBlank`), 길이 1~50 (`String.length()`) | 400 VALIDATION_ERROR |
| `X-User-Id` | `POST /api/orders/{id}/pay` (선택) | **검증하지 않음.** 있으면 값 그대로 멱등 지문에 포함, 없으면 "없음"으로 지문에 포함. 소유권 검사 없음(인증·인가 범위 밖) | — |
| `X-User-Id` | 그 외 | 무시 | — |
| `Idempotency-Key` | `POST /api/orders`, `POST /api/orders/{id}/pay` (필수) | 존재, 공백 아님, 길이 1~64 | 400 VALIDATION_ERROR |

- 구현: `@RequestHeader("X-User-Id") @NotBlank @Size(max = 50) String userId`, `@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String key`. 누락은 `MissingRequestHeaderException`, 제약 위반은 `HandlerMethodValidationException` → 둘 다 400 (6장).

### 0.6 설정 키 ↔ 환경 변수 (C4)
`src/main/resources/application.yml` 전문:

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/orders}
    username: ${SPRING_DATASOURCE_USERNAME:orders}
    password: ${SPRING_DATASOURCE_PASSWORD:orders}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 2        # 테스트에서 컨텍스트가 여러 개 캐시돼도 PG max_connections(100) 초과 방지
  jpa:
    open-in-view: false      # 필수: 멱등 트랜잭션과 업무 트랜잭션이 커넥션을 공유/중첩하지 않게
    hibernate:
      ddl-auto: none         # 스키마의 단일 출처는 Flyway
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false

server:
  port: ${SERVER_PORT:8080}

payment:
  gateway:
    url: ${PAYMENT_GATEWAY_URL:http://localhost:9090}
    connect-timeout: PT2S
    read-timeout: PT2S

order:
  payment-ttl: ${ORDER_PAYMENT_TTL:PT15M}
  expiry-sweep-delay-ms: 200
```

- `ORDER_PAYMENT_TTL`은 **명시적 플레이스홀더가 필수**다. relaxed binding으로는 `ORDER_PAYMENT_TTL` → `order.payment.ttl`로 해석되어 `order.payment-ttl`에 닿지 않는다.
- 바인딩: `@ConfigurationProperties("payment.gateway") record PaymentGatewayProperties(URI url, Duration connectTimeout, Duration readTimeout)`, `@ConfigurationProperties("order") record OrderProperties(Duration paymentTtl, long expirySweepDelayMs)`. `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties`로 등록.
- 테스트 덮어쓰기: `@SpringBootTest(properties = "order.payment-ttl=PT3S")`, `@DynamicPropertySource`로 `payment.gateway.url`에 PG 더블 URL 주입.

---

## 1. 엔드포인트 목록

| # | 메서드·경로 | 성공 | 요구 |
|---|---|---|---|
| E1 | `POST /api/products` | 201 + Location, ProductResponse | R1.1, R1.2 |
| E2 | `GET /api/products/{id}` | 200 ProductResponse | R1.3 |
| E3 | `POST /api/coupons` | 201 + Location, CouponResponse | R2.1, R2.2 |
| E4 | `GET /api/coupons/{code}` | 200 CouponResponse | R2.3 |
| E5 | `POST /api/orders` (멱등) | 201 + Location, OrderResponse | R3, R4 |
| E6 | `GET /api/orders/{id}` | 200 OrderResponse | R3.5 |
| E7 | `GET /api/orders` | 200 OrderPageResponse | R9 |
| E8 | `POST /api/orders/{id}/pay` (멱등) | 200 OrderResponse | R4, R5 |
| E9 | `POST /api/orders/{id}/cancel` | 200 OrderResponse | R7 |
| E10 | `POST /api/orders/{id}/ship` | 200 OrderResponse | R8 |
| E11 | `POST /api/orders/{id}/deliver` | 200 OrderResponse | R8 |

E9~E11은 요청 본문 없음(보내도 무시), 멱등 키 불필요.

---

## 2. 스키마

### 2.1 ProductResponse
| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `id` | long | N | |
| `name` | string | N | 입력 그대로 저장(trim 안 함) |
| `price` | long | N | |
| `stock` | int | N | 판매되지 않은 보유 수량 |
| `reserved` | int | N | 결제 대기 주문이 잡은 수량 |
| `available` | int | N | `stock - reserved` (저장하지 않는 계산 필드) |

### 2.2 CreateProductRequest (E1)
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `name` | string | Y | `@NotBlank`, `@Size(max=100)` |
| `price` | Long | Y | `@Min(1) @Max(10_000_000)` |
| `stock` | Integer | Y | `@Min(0) @Max(1_000_000)` |

### 2.3 CouponResponse
| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `code` | string | N | |
| `type` | string `FIXED`\|`RATE` | N | |
| `value` | long | N | FIXED: 원, RATE: % |
| `minOrderAmount` | long | N | 생략 입력 시 0 |
| `maxDiscountAmount` | long | **Y** | null = 상한 없음 |
| `totalQuantity` | long | N | |
| `usedCount` | long | N | 사용 중 주문 수 |
| `validFrom` | string(OffsetDateTime, UTC) | N | |
| `validUntil` | string(OffsetDateTime, UTC) | N | |

### 2.4 CreateCouponRequest (E3)
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `code` | string | Y | `@NotNull @Pattern("^[A-Z0-9]{4,20}$")` |
| `type` | enum CouponType | Y | `FIXED` \| `RATE` (그 외 → 파싱 실패 400) |
| `value` | Long | Y | `@Min(1)`; **교차 검증** `type=RATE`면 `value ≤ 100` |
| `minOrderAmount` | Long | N | `@Min(0)`, null이면 0으로 저장 |
| `maxDiscountAmount` | Long | N | `@Min(1)`, null 허용(제한 없음) |
| `totalQuantity` | Long | Y | `@Min(1)` |
| `validFrom` | OffsetDateTime | Y | 오프셋 필수 |
| `validUntil` | OffsetDateTime | Y | **교차 검증** `validFrom < validUntil` (UTC·μs 정규화 후 비교) |

### 2.5 OrderResponse (E5, E6, E7 원소, E8, E9, E10, E11)
| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `id` | long | N | |
| `userId` | string | N | 생성 시 `X-User-Id` |
| `status` | string | N | `PENDING_PAYMENT`·`PAID`·`PAYMENT_FAILED`·`EXPIRED`·`CANCELLED`·`REFUNDED`·`SHIPPED`·`DELIVERED` |
| `items` | array<OrderItemResponse> | N | **요청 순서 그대로**(`line_no` 오름차순) |
| `items[].productId` | long | N | |
| `items[].quantity` | int | N | |
| `items[].unitPrice` | long | N | 주문 시점 상품 가격 |
| `couponCode` | string | **Y** | 쿠폰 미사용이면 null |
| `subtotal` | long | N | Σ(unitPrice × quantity) |
| `discount` | long | N | 쿠폰 없으면 0 |
| `totalPrice` | long | N | subtotal − discount |
| `createdAt` | string(OffsetDateTime, UTC) | N | |
| `expiresAt` | string(OffsetDateTime, UTC) | N | createdAt + `order.payment-ttl` |
| `paidAt` | string(OffsetDateTime, UTC) | **Y** | 결제 승인 전 null |

`paymentId`는 응답에 노출하지 않는다.

### 2.6 CreateOrderRequest (E5)
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `items` | `List<@NotNull @Valid OrderItemRequest>` | Y | `@NotNull @Size(min=1, max=20)`; **교차 검증** productId 중복 불가 |
| `items[].productId` | Long | Y | `@NotNull` (값 범위 검증 없음, 없으면 404) |
| `items[].quantity` | Integer | Y | `@NotNull @Min(1) @Max(1000)` |
| `couponCode` | string | N | null/생략 = 쿠폰 없음. 값이 있으면 형식 검증 없이 그대로 조회(없으면 404) |

### 2.7 PayRequest (E8)
| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `cardToken` | string | Y | `@NotBlank` |

### 2.8 OrderPageResponse (E7)
| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `content` | array<OrderResponse> | N | 빈 배열 가능 |
| `nextCursor` | string | **Y** | 다음 페이지 없으면 null |

### 2.9 교차 검증 위치
- 중복 productId, RATE value ≤ 100, validFrom < validUntil은 **400 단계**다. DTO의 `@AssertTrue` 메서드 또는 컨트롤러에서 멱등 처리(`begin`) **이전**에 수행하고 위반 시 `ApiException(VALIDATION_ERROR)`를 던진다. 어느 쪽이든 멱등 키 선점보다 먼저 끝나야 한다.

---

## 3. 엔드포인트 상세

### E1 `POST /api/products`
- 요청 2.2 → 201, `Location: /api/products/{id}`, 본문 2.1 (`reserved`=0, `available`=`stock`)
- 오류: 400 VALIDATION_ERROR

### E2 `GET /api/products/{id}`
- 200 2.1 / 400(id 형식) / 404 PRODUCT_NOT_FOUND

### E3 `POST /api/coupons`
- 요청 2.4 → 201, `Location: /api/coupons/{code}`, 본문 2.3 (`usedCount`=0)
- 흐름: 400 검증 → `existsByCode` 이면 409 DUPLICATE_COUPON_CODE → insert. 경합으로 `uk_coupons_code` 위반 시에도 409 DUPLICATE_COUPON_CODE (6.3 참조).

### E4 `GET /api/coupons/{code}`
- 200 2.3 / 404 COUPON_NOT_FOUND

### E5 `POST /api/orders` (멱등, scope=`CREATE_ORDER`)
- 헤더 `X-User-Id`, `Idempotency-Key` 필수. 요청 2.6 → 201, `Location: /api/orders/{id}`, 본문 2.5 (`status`=`PENDING_PAYMENT`, `paidAt`=null)
- **처리 순서 (C3 보장)** — 각 단계는 앞 단계를 통과해야만 실행되므로 우선순위가 코드 흐름으로 강제된다:
  1. **[400]** 헤더·본문 Bean Validation(인자 바인딩 단계에서 Spring이 수행) → 교차 검증(중복 productId).
  2. **[422/409]** `IdempotencyService.begin(CREATE_ORDER, key, fingerprint)` (4장). 이후 업무 처리는 `execute` 래퍼 안에서.
  3. **업무 트랜잭션** `@Transactional OrderService.create(userId, request)` (READ COMMITTED):
     1. `now = clock 기준 μs truncate`
     2. **[404]** 상품 락: `SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC` + `@Lock(PESSIMISTIC_WRITE)` — **productId 오름차순으로 락 획득**(R10.4). 결과 수 < 요청 수면 404 PRODUCT_NOT_FOUND (detail에 누락 id 나열).
     3. **[404]** couponCode가 있으면 쿠폰 락: `SELECT c FROM Coupon c WHERE c.code = :code` + `PESSIMISTIC_WRITE`. 없으면 404 COUPON_NOT_FOUND.
     4. **[409 재고]** 요청의 각 항목에 대해 `stock - reserved < quantity`인 것이 하나라도 있으면 409 INSUFFICIENT_STOCK.
     5. subtotal 계산 (`Math.multiplyExact`/`addExact` 사용, 최대 2×10¹¹이라 long 안전).
     6. **[409 쿠폰]** 쿠폰이 있으면 아래 순서로 검사 — **NOT_APPLICABLE 계열을 먼저, EXHAUSTED를 마지막에**:
        1. `validFrom ≤ now < validUntil` 아니면 → COUPON_NOT_APPLICABLE
        2. `subtotal < minOrderAmount` → COUPON_NOT_APPLICABLE
        3. 같은 사용자가 이 쿠폰을 사용 중인 주문 존재 (`exists orders where coupon_code=:code and user_id=:userId and status in ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED')`) → COUPON_NOT_APPLICABLE. 쿠폰 행 락을 쥔 뒤 실행하므로 READ COMMITTED의 문장 단위 스냅샷이 선행 트랜잭션 커밋 결과를 본다(R10.3).
        4. `usedCount ≥ totalQuantity` → COUPON_EXHAUSTED
     7. 할인 계산(R2.4): FIXED `d=value`, RATE `d=subtotal*value/100`(음 아닌 정수 나눗셈 = floor). `maxDiscountAmount != null`이면 `d=min(d,max)`, 마지막 `d=min(d,subtotal)`. `totalPrice=subtotal-d`. 쿠폰 없으면 `d=0`.
     8. 반영: 각 상품 `reserved += quantity`; 쿠폰 `usedCount += 1`; 주문 insert (`status=PENDING_PAYMENT`, `createdAt=now`, `expiresAt=(now+ttl).truncatedTo(MICROS)`, `items`는 요청 순서대로 `lineNo=0..n-1`, `unitPrice=product.price`). 커밋.
  4. 2xx → `complete`로 응답 저장. 예외 → `release` 후 예외 재전파.
- 안전망: 부분 유니크 인덱스 `uk_orders_coupon_user_active` 위반 시 409 COUPON_NOT_APPLICABLE (6.3).
- 원자성(R3.4): 3.의 모든 변경은 한 트랜잭션. 어떤 단계든 예외 → 전체 롤백.

### E6 `GET /api/orders/{id}`
- 200 2.5 / 400(id 형식) / 404 ORDER_NOT_FOUND
- 만료 대상인데 아직 스윕 전이면 `PENDING_PAYMENT`가 보일 수 있다(최대 스윕 주기 ≈ 200ms + 처리시간, R6.2 2초 이내).

### E7 `GET /api/orders?userId&status&size&cursor`
| 쿼리 | 타입 | 기본 | 규칙 | 위반 |
|---|---|---|---|---|
| `userId` | string | 없음 | 있으면 공백 아님·1~50자 (`@Size(min=1,max=50)` + 공백만 금지) | 400 |
| `status` | enum OrderStatus | 없음 | 8개 값 중 하나(대소문자 정확). 빈 문자열은 필터 없음으로 취급(Spring 변환 결과 null) | 400 |
| `size` | Integer | 20 | `@Min(1) @Max(100)`, 정수 아님 → 400 | 400 |
| `cursor` | string | 없음 | 아래 형식. 빈 문자열은 없음으로 취급 | 400 |

- 정렬: `created_at DESC, id DESC` (R9.4).
- **커서 형식**: `Base64.getUrlEncoder().withoutPadding()`로 인코딩한 UTF-8 문자열 `v1:{createdAtEpochMicros}:{id}`.
  - epochMicros = `instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1_000`.
  - 디코딩: `Base64.getUrlDecoder()` 실패, 정규식 `^v1:(-?\d{1,19}):(\d{1,19})$` 불일치, `Long.parseLong` 오버플로, `id ≤ 0` → 전부 400 VALIDATION_ERROR (`ApiException`).
- 쿼리(키셋): 존재하는 필터만 동적으로 붙인다(PG 네이티브 쿼리의 `:p IS NULL` 패턴은 타입 추론 오류가 나므로 금지. JPQL 문자열 조립 또는 Criteria 사용).
  ```
  where [o.userId = :userId] and [o.status = :status]
    and [(o.createdAt < :cAt or (o.createdAt = :cAt and o.id < :cId))]   -- cursor 있을 때만
  order by o.createdAt desc, o.id desc
  limit size + 1
  ```
  `size+1`행을 읽어 초과분이 있으면 `nextCursor = encode(페이지 마지막 원소)`, 아니면 null. items는 페이지의 주문 id 집합으로 한 번에 로드(`@BatchSize` 또는 `in` 조회)해 N+1 방지.
- R9.5: `createdAt`·`id`는 불변이고 μs truncate로 엔티티=DB 값이므로 첫 페이지 시점 주문은 중복·누락 없이 한 번씩 나온다. (status 필터 사용 중 주문 상태가 바뀌면 필터에서 빠질 수 있다 — 요구 범위 밖.)

### E8 `POST /api/orders/{id}/pay` (멱등, scope=`PAY_ORDER`)
- 헤더 `Idempotency-Key` 필수, `X-User-Id` 선택(0.5). 요청 2.7 → 결과별 응답.
- **처리 순서 (C3 보장)**:
  1. **[400]** path id(Long), `Idempotency-Key`, 본문 `cardToken`.
  2. **[422/409]** `IdempotencyService.begin(PAY_ORDER, key, fingerprint)`.
  3. `PaymentFacade.pay(id, key, cardToken)` — **비트랜잭션** 파사드가 업무 트랜잭션을 호출하고, 결과에 따라 커밋 후 402를 던진다:
     ```
     outcome = paymentTxService.payInTx(id, key, cardToken)   // @Transactional
     if (outcome == DECLINED) throw ApiException(PAYMENT_DECLINED)  // 커밋 이후라 PAYMENT_FAILED·복원이 유지됨
     return 200 OrderResponse(outcome.order)
     ```
  4. `payInTx` (@Transactional, READ COMMITTED):
     1. **[404]** 주문 행 락 `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`). 없으면 404 ORDER_NOT_FOUND.
     2. **[409]** `status != PENDING_PAYMENT` 또는 `!now.isBefore(expiresAt)` (만료 = `now ≥ expiresAt`) → 409 INVALID_STATE. 상태 변경 없음(만료 전이는 스윕 담당).
     3. `totalPrice == 0` → PG 호출 없이 승인 처리(R5.7), `paymentId=null`.
     4. 아니면 **주문 행 락을 쥔 채로** PG 호출 `pgClient.pay(idempotencyKey=클라이언트 키, orderId, amount=totalPrice, cardToken)`.
        - 장애(7.3) → `PaymentGatewayUnavailableException` → 트랜잭션 롤백(아무것도 바뀌지 않음) → 503.
     5. **APPROVED**: 상품 락(productId 오름차순) → 각 상품 `stock -= q`, `reserved -= q`; 주문 `status=PAID`, `paidAt=now(μs)`, `paymentId=응답 paymentId`. 커밋 → 200.
     6. **DECLINED**: 상품 락(오름차순) → `reserved -= q`; 쿠폰이 있으면 쿠폰 락 → `usedCount -= 1`; 주문 `status=PAYMENT_FAILED`, `paymentId` 저장. 커밋 → outcome DECLINED → 파사드가 402.
  5. 2xx(200)만 `complete`. 402·503·404·409 → `release`.
- **R10.5 보장**: 같은 주문의 동시 결제(키 다름)는 주문 행 락에서 직렬화된다. 선두가 PG 호출~커밋까지 락을 쥐므로 후속은 `PAID`(또는 `PAYMENT_FAILED`)를 보고 409 INVALID_STATE. → PG 결제 요청 최대 1회, 성공 1건.
- **R5.6 무모순**: 503은 롤백이므로 주문·재고·쿠폰 불변. 중간 상태를 저장하지 않으므로 원복 로직이 필요 없다. 같은 키 재시도 시 PG 계약(같은 키 → 최초 결과)에 의해 이중 결제가 없다.
- 락 보유 시간: 최대 connect 2s + read 2s. 대기 요청은 커넥션을 쥔 채 대기하므로 풀 20 + `open-in-view: false` + 멱등 트랜잭션 비중첩(4.4)이 필수.

### E9 `POST /api/orders/{id}/cancel`
- `@Transactional`:
  1. 주문 행 락. 없으면 404.
  2. `PENDING_PAYMENT` 이고 `now < expiresAt` → 상품 락(오름차순) `reserved -= q` → 쿠폰 있으면 `usedCount -= 1` → `CANCELLED`. 200.
  3. `PENDING_PAYMENT` 이고 `now ≥ expiresAt` → 409 INVALID_STATE (스윕이 곧 EXPIRED 처리).
  4. `PAID` → `paymentId != null`이면 **주문 락을 쥔 채** `pgClient.refund(paymentId)`; 장애 → 롤백 503. `paymentId == null`(0원 결제)이면 PG 생략. 성공 → 상품 락(오름차순) `stock += q` → 쿠폰 `usedCount -= 1` → `REFUNDED`. 200.
  5. 그 외 → 409 INVALID_STATE.

### E10 `POST /api/orders/{id}/ship` / E11 `POST /api/orders/{id}/deliver`
- `@Transactional`: 주문 행 락 → 404 → `PAID→SHIPPED` / `SHIPPED→DELIVERED`, 그 외 409 INVALID_STATE → 200.

### 3.1 결제 만료 스윕 (R6)
- `@EnableScheduling`을 설정 클래스에 선언. `@Scheduled(fixedDelayString = "${order.expiry-sweep-delay-ms:200}")`.
- 알고리즘:
  1. 후보 조회(락 없음): `select id from orders where status='PENDING_PAYMENT' and expires_at <= :now order by expires_at, id limit 100`.
  2. **주문 하나당 트랜잭션 하나** (여러 주문을 한 트랜잭션에서 처리하면 상품 락 순서가 오름차순이 아니게 되어 금지):
     - `select ... from orders where id=:id and status='PENDING_PAYMENT' and expires_at <= :now for update skip locked` (네이티브 쿼리). 없으면 건너뜀(이미 처리/결제 중).
     - 상품 락(오름차순) `reserved -= q` → 쿠폰 `usedCount -= 1` → `status=EXPIRED`. 커밋.
  3. 예외는 로그만 남기고 다음 주문 진행.
- 여러 애플리케이션 컨텍스트(테스트 캐시)가 동시에 스윕해도 `SKIP LOCKED` + 상태 재확인으로 안전하다.
- 결제와 경합: 결제가 주문 락을 쥐고 있으면 스윕은 건너뛰고, 결제가 커밋한 뒤엔 상태가 바뀌어 대상 아님.

### 3.2 전역 락 순서 (데드락 회피)
모든 트랜잭션은 **주문 행 → 상품 행(productId 오름차순) → 쿠폰 행** 순으로만 락을 잡는다. 주문 생성은 기존 주문 락이 없으므로 상품 → 쿠폰. 한 트랜잭션에서 두 주문의 상품을 섞어 잠그지 않는다.

### 3.3 사용 복원 공통 루틴
`releaseReservation(order)`: 상품 오름차순 락 후 `reserved -= q` (PENDING에서 벗어날 때: CANCELLED·EXPIRED·PAYMENT_FAILED)
`restoreStock(order)`: 상품 오름차순 락 후 `stock += q` (REFUNDED)
`restoreCoupon(order)`: `couponCode != null`이면 쿠폰 락 후 `usedCount -= 1` (CANCELLED·EXPIRED·PAYMENT_FAILED·REFUNDED)
상태 전이 후에는 해당 주문이 "사용 중" 집합에서 빠지므로 같은 사용자가 쿠폰을 다시 쓸 수 있다(R2.6).

---

## 4. 멱등성 프로토콜 (R4)

### 4.1 저장소
테이블 `idempotency_keys` (02 문서). 유니크 `(scope, idem_key)` — scope는 `CREATE_ORDER` / `PAY_ORDER`로 **엔드포인트별 독립 키 공간**(R4.1). 사용자별로 키 공간을 나누지 않으며, 다른 사용자가 같은 키를 쓰면 지문이 달라 422다.

### 4.2 요청 지문
```
canonicalBody = CANONICAL_MAPPER.writeValueAsString(validatedRequestDto)
  // CANONICAL_MAPPER: JsonMapper.builder()
  //   .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
  //   .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
  // null 필드 포함. items 배열 순서는 보존(순서가 다르면 다른 요청).
userPart  = (xUserId == null) ? "-" : "U:" + xUserId
path      = "/api/orders"                       (CREATE_ORDER)
          | "/api/orders/" + id + "/pay"        (PAY_ORDER, 파싱된 Long id로 재구성)
fingerprint = lowerHex(SHA-256(UTF-8(scope + "\n" + userPart + "\n" + path + "\n" + canonicalBody)))
```
- 공백·필드 순서·알 수 없는 필드·`"couponCode": null`과 생략의 차이는 지문에 영향 없음(의미 동일).

### 4.3 상태 기계와 흐름
```
execute(scope, key, fp, supplier):
  r = begin(scope, key, fp)
  if r is Replay(status, body, location) → 그대로 반환 (Content-Type: application/json, Location 있으면 포함)
  try:
     resp = supplier.get()                // 업무 처리 (자체 트랜잭션)
     if resp.status is 2xx: complete(r.id, resp.status, toJson(resp.body), resp.location)
     else: release(r.id)
     return resp
  catch (Throwable t):
     release(r.id); throw t                // 오류 응답은 저장하지 않음(R4.4)
```
- `begin` (자체 짧은 트랜잭션, 커밋 후 커넥션 반환):
  1. `INSERT INTO idempotency_keys(scope, idem_key, request_hash, status, created_at) VALUES (?,?,?,'IN_PROGRESS',?) ON CONFLICT (scope, idem_key) DO NOTHING RETURNING id` → 행 반환 시 **선점 성공**(Owner).
  2. 충돌이면 `SELECT ... WHERE scope=? AND idem_key=?`:
     - `request_hash != fp` → 422 IDEMPOTENCY_KEY_MISMATCH (**지문 비교가 진행 상태보다 먼저**)
     - `status = COMPLETED` → Replay
     - `status = IN_PROGRESS` → 409 IDEMPOTENCY_IN_PROGRESS
     - 행 없음(그 사이 선점자가 release) → 1로 돌아가 재시도(최대 3회, 초과 시 409 IDEMPOTENCY_IN_PROGRESS)
- `complete`: `UPDATE ... SET status='COMPLETED', response_status=?, response_body=?, response_location=?, completed_at=? WHERE id=? AND status='IN_PROGRESS'`
- `release`: `DELETE FROM idempotency_keys WHERE id=? AND status='IN_PROGRESS'`
- 구현: `JdbcTemplate` + `@Transactional(propagation = REQUIRES_NEW)` (방어적). **`begin`/`complete`/`release`는 업무 트랜잭션 바깥에서 순차 호출**한다 — 컨트롤러·파사드에 `@Transactional` 금지. 한 요청이 동시에 커넥션 2개를 쥐지 않게 하여 풀 고갈 교착을 막는다.
- 재생 본문: 저장된 JSON 문자열을 `objectMapper.readTree`로 `JsonNode`로 만들어 반환(같은 Spring `ObjectMapper`로 직렬화했으므로 형태 동일). 상태코드·Location 동일.
- 저장 대상: E5의 201, E8의 200. 그 외 전부 release.
- 동시 동일 키(R4.5): 두 번째 요청의 `INSERT ... ON CONFLICT`는 선점 트랜잭션 커밋까지 대기 후 충돌 → `IN_PROGRESS`면 409, 이미 완료면 재생. 업무 처리는 Owner 한 번뿐.
- 알려진 한계: 업무 커밋 후 `complete` 전에 프로세스가 죽으면 키가 `IN_PROGRESS`로 남아 이후 409 (키 만료는 범위 밖).

---

## 5. PG 클라이언트 계약

### 5.1 구성
- `RestClient` (빈 이름 `paymentGatewayRestClient`), `baseUrl = payment.gateway.url`.
- 요청 팩토리: `JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build())` + `setReadTimeout(readTimeout)` (둘 다 PT2S). `SimpleClientHttpRequestFactory`(connect/read 2s)도 허용.
- 인터페이스: `PaymentGatewayClient { PgPaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken); void refund(String paymentId); }`

### 5.2 호출
| 용도 | 요청 | 기대 응답 |
|---|---|---|
| 결제 | `POST {url}/v1/payments`, 헤더 `Idempotency-Key: {클라이언트가 보낸 키 그대로}`, `Content-Type: application/json`, 본문 `{"orderId": long, "amount": long(totalPrice), "cardToken": string}` | 200 `{"paymentId": string, "status": "APPROVED"\|"DECLINED"}` |
| 환불 | `POST {url}/v1/payments/{paymentId}/refund`, 본문 없음 | 200 `{"paymentId": string, "status": "REFUNDED"}` |

### 5.3 결과 판정
| PG 결과 | 처리 |
|---|---|
| 200 + `APPROVED` | 승인 (R5.4) |
| 200 + `DECLINED` | 거절 (R5.5) → 402 |
| 200 + 환불 `REFUNDED` | 환불 성공 (R7.3) |
| 5xx (`HttpServerErrorException`) | 503 PAYMENT_GATEWAY_UNAVAILABLE |
| 연결 실패·타임아웃 (`ResourceAccessException`) | 503 |
| 그 외 계약 밖 응답(4xx, 본문 파싱 불가, 알 수 없는 status) | 503 (장애 취급, 상태 불변) |

### 5.4 테스트 더블 권고
- 1안(권장): **WireMock** — `testImplementation 'org.wiremock:wiremock-standalone:3.13.1'` (standalone jar라 Spring Boot의 Jetty/Jackson과 충돌 없음; Boot BOM이 버전을 관리하지 않으므로 버전 명시). `WireMockServer(options().dynamicPort())`를 static으로 띄우고 `@DynamicPropertySource`로 `payment.gateway.url` 주입.
  - 승인/거절: `stubFor(post("/v1/payments").willReturn(okJson(...)))`
  - 5xx: `aResponse().withStatus(500)`, 타임아웃: `withFixedDelay(3000)`, 연결 실패: 닫힌 포트를 URL로 쓰거나 `withFault(Fault.CONNECTION_RESET_BY_PEER)`
  - R5.3/R10.5 검증: `verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments")).withHeader("Idempotency-Key", equalTo(key)))`
- 2안(의존성 불가 시): JDK `com.sun.net.httpserver.HttpServer` + `AtomicInteger` 호출 카운트 + `Thread.sleep`로 지연.
- 그 밖에 R6 대기용 `testImplementation 'org.awaitility:awaitility'` (Boot BOM 관리) 권장.

---

## 6. 에러 계약 (R11)

### 6.1 형식
모든 오류 응답: `Content-Type: application/problem+json`, 본문 RFC 9457.
```json
{
  "type": "https://example.com/problems/insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "product 3: available 0, requested 1",
  "instance": "/api/orders",
  "code": "INSUFFICIENT_STOCK"
}
```
- `type` = `"https://example.com/problems/" + code.toLowerCase().replace('_', '-')`
- `title` = 아래 표 고정 문자열, `status` = HTTP 상태, `detail` = 사람이 읽는 상세, `instance` = 요청 URI 경로, `code` = 아래 표.
- VALIDATION_ERROR는 선택 확장 `errors: [{"field": string, "message": string}]`를 둘 수 있다.
- 구현: `ErrorCode` enum(status, title) + `ApiException(ErrorCode, String detail)`. `@RestControllerAdvice`에서 Spring `ProblemDetail`을 만들어 `setProperty("code", ...)`, `ResponseEntity.status(..).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)` — **Content-Type을 명시적으로 지정**한다(Accept 협상과 무관하게 problem+json 보장).
- advice는 `ResponseEntityExceptionHandler`를 **상속하지 않고** 아래 예외를 명시적으로 처리한다(상속 시 같은 예외에 대한 핸들러 중복으로 기동 실패).

### 6.2 code 표
| code | HTTP | title |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Validation failed |
| `PAYMENT_DECLINED` | 402 | Payment declined |
| `PRODUCT_NOT_FOUND` | 404 | Product not found |
| `COUPON_NOT_FOUND` | 404 | Coupon not found |
| `ORDER_NOT_FOUND` | 404 | Order not found |
| `INSUFFICIENT_STOCK` | 409 | Insufficient stock |
| `COUPON_NOT_APPLICABLE` | 409 | Coupon not applicable |
| `COUPON_EXHAUSTED` | 409 | Coupon exhausted |
| `DUPLICATE_COUPON_CODE` | 409 | Duplicate coupon code |
| `INVALID_STATE` | 409 | Invalid order state |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | Idempotent request in progress |
| `IDEMPOTENCY_KEY_MISMATCH` | 422 | Idempotency key mismatch |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 503 | Payment gateway unavailable |

### 6.3 예외 → 응답 매핑
| 예외 | 결과 |
|---|---|
| `HttpMessageNotReadableException` (JSON 파싱 실패, 본문 누락, 타입 불일치, 정수 오버플로, 알 수 없는 enum, 오프셋 없는 시각) | 400 VALIDATION_ERROR |
| `MethodArgumentNotValidException` (`@Valid @RequestBody`) | 400 VALIDATION_ERROR |
| `HandlerMethodValidationException` (헤더·쿼리 제약, 그리고 메서드 검증이 켜진 경우의 본문 오류) | 400 VALIDATION_ERROR |
| `MissingRequestHeaderException` / `MissingServletRequestParameterException` | 400 VALIDATION_ERROR |
| `MethodArgumentTypeMismatchException` (path id, size, status 변환 실패) | 400 VALIDATION_ERROR |
| `jakarta.validation.ConstraintViolationException` | 400 VALIDATION_ERROR |
| `HttpMediaTypeNotSupportedException` (JSON 아닌 Content-Type) | 400 VALIDATION_ERROR |
| `ApiException` | 해당 ErrorCode |
| `PaymentGatewayUnavailableException` | 503 PAYMENT_GATEWAY_UNAVAILABLE |
| `DataIntegrityViolationException` 중 제약명 `uk_coupons_code` | 409 DUPLICATE_COUPON_CODE |
| `DataIntegrityViolationException` 중 제약명 `uk_orders_coupon_user_active` | 409 COUPON_NOT_APPLICABLE |
| 그 외 `DataIntegrityViolationException`·기타 예외 | 500 (버그. code 없는 ProblemDetail 허용) |

제약명 추출: 원인 체인에서 `org.hibernate.exception.ConstraintViolationException#getConstraintName()` 또는 `org.postgresql.util.PSQLException#getServerErrorMessage().getConstraint()`. 비교는 소문자.

### 6.4 C3 우선순위 요약 (코드 흐름으로 보장)
| 단계 | 시점 | 엔드포인트 |
|---|---|---|
| 1. 400 | 인자 바인딩·Bean Validation(Spring) → 컨트롤러 교차 검증 | 전체 |
| 2. 422 → 409(IN_PROGRESS) | `IdempotencyService.begin` (지문 비교 먼저) | E5, E8 |
| 3. 404 | 업무 트랜잭션 첫 조회. E5는 상품 → 쿠폰 | E2, E4~E11 |
| 4. 409 | E5: INSUFFICIENT_STOCK → COUPON_NOT_APPLICABLE(기간→최소금액→사용 중) → COUPON_EXHAUSTED. E8~E11: INVALID_STATE. E3: DUPLICATE_COUPON_CODE | |
| 5. 402·503 | PG 호출 결과 | E8, E9 |

---

## 7. 정합 요약 (구현·검증 기준표)

### 7.1 응답 필드 ↔ 컬럼 대응표

**ProductResponse ↔ `products`**
| 응답 필드 | JSON 타입 | 컬럼 | SQL 타입 | 엔티티 필드(Java) | 비고 |
|---|---|---|---|---|---|
| `id` | long | `id` | BIGINT IDENTITY | `Long id` | |
| `name` | string | `name` | VARCHAR(100) NOT NULL | `String name` | |
| `price` | long | `price` | BIGINT NOT NULL | `long price` | |
| `stock` | int | `stock` | INTEGER NOT NULL | `int stock` | |
| `reserved` | int | `reserved` | INTEGER NOT NULL DEFAULT 0 | `int reserved` | |
| `available` | int | — | — | 계산 `stock - reserved` | 저장 안 함 |
| (미노출) | | `created_at` | TIMESTAMPTZ NOT NULL | `OffsetDateTime createdAt` | |

**CouponResponse ↔ `coupons`**
| 응답 필드 | JSON 타입 | 컬럼 | SQL 타입 | 엔티티 필드 | 비고 |
|---|---|---|---|---|---|
| `code` | string | `code` | VARCHAR(20) NOT NULL UNIQUE | `String code` | |
| `type` | string | `discount_type` | VARCHAR(10) NOT NULL | `CouponType discountType` (`@Enumerated(STRING)`) | 컬럼명 다름(예약어 회피) |
| `value` | long | `discount_value` | BIGINT NOT NULL | `long discountValue` | 컬럼명 다름(HQL `value` 회피) |
| `minOrderAmount` | long | `min_order_amount` | BIGINT NOT NULL DEFAULT 0 | `long minOrderAmount` | 입력 null → 0 |
| `maxDiscountAmount` | long\|null | `max_discount_amount` | BIGINT NULL | `Long maxDiscountAmount` | nullable 일치 |
| `totalQuantity` | long | `total_quantity` | BIGINT NOT NULL | `long totalQuantity` | |
| `usedCount` | long | `used_count` | BIGINT NOT NULL DEFAULT 0 | `long usedCount` | |
| `validFrom` | string | `valid_from` | TIMESTAMPTZ NOT NULL | `OffsetDateTime validFrom` | UTC·μs |
| `validUntil` | string | `valid_until` | TIMESTAMPTZ NOT NULL | `OffsetDateTime validUntil` | UTC·μs |
| (미노출) | | `id`, `created_at` | BIGINT IDENTITY, TIMESTAMPTZ | `Long id`, `OffsetDateTime createdAt` | 대리키 |

**OrderResponse ↔ `orders` / `order_items`**
| 응답 필드 | JSON 타입 | 컬럼 | SQL 타입 | 엔티티 필드 | 비고 |
|---|---|---|---|---|---|
| `id` | long | `orders.id` | BIGINT IDENTITY | `Long id` | |
| `userId` | string | `orders.user_id` | VARCHAR(50) NOT NULL | `String userId` | |
| `status` | string | `orders.status` | VARCHAR(20) NOT NULL | `OrderStatus status` (`@Enumerated(STRING)`) | |
| `items[]` | array | `order_items` (`order_id` FK) | | `List<OrderItem> items` (`@OrderBy("lineNo ASC")`) | 순서 = `line_no` |
| `items[].productId` | long | `order_items.product_id` | BIGINT NOT NULL FK | `long productId` | |
| `items[].quantity` | int | `order_items.quantity` | INTEGER NOT NULL | `int quantity` | |
| `items[].unitPrice` | long | `order_items.unit_price` | BIGINT NOT NULL | `long unitPrice` | |
| `couponCode` | string\|null | `orders.coupon_code` | VARCHAR(20) NULL FK | `String couponCode` | nullable 일치 |
| `subtotal` | long | `orders.subtotal` | BIGINT NOT NULL | `long subtotal` | |
| `discount` | long | `orders.discount` | BIGINT NOT NULL | `long discount` | |
| `totalPrice` | long | `orders.total_price` | BIGINT NOT NULL | `long totalPrice` | |
| `createdAt` | string | `orders.created_at` | TIMESTAMPTZ NOT NULL | `OffsetDateTime createdAt` | UTC·μs, 커서 키 |
| `expiresAt` | string | `orders.expires_at` | TIMESTAMPTZ NOT NULL | `OffsetDateTime expiresAt` | UTC·μs |
| `paidAt` | string\|null | `orders.paid_at` | TIMESTAMPTZ NULL | `OffsetDateTime paidAt` | nullable 일치 |
| (미노출) | | `orders.payment_id` | VARCHAR(255) NULL | `String paymentId` | 환불용 |
| (미노출) | | `order_items.id`, `order_items.line_no`, `order_items.order_id` | | | |

**OrderPageResponse**: `content` = 위 OrderResponse 목록, `nextCursor` = `base64url("v1:" + epochMicros(orders.created_at) + ":" + orders.id)` (컬럼 직접 대응 없음).

**멱등 재생 ↔ `idempotency_keys`** (API 필드 아님): 재생 상태코드 = `response_status`, 본문 = `response_body`, Location = `response_location`.

**요청 필수성 ↔ nullable 정합**
| 요청 필드 | 필수 | 컬럼 nullable | 정합 |
|---|---|---|---|
| product `name`/`price`/`stock` | Y | NOT NULL | 일치 |
| coupon `minOrderAmount` | N | NOT NULL DEFAULT 0 | 서비스에서 null → 0 |
| coupon `maxDiscountAmount` | N | NULL | 일치 |
| coupon 나머지 | Y | NOT NULL | 일치 |
| order `couponCode` | N | NULL | 일치 |
| `X-User-Id` (E5) | Y | `orders.user_id` NOT NULL | 일치 |

### 7.2 제약·검증 위반 ↔ HTTP 상태·code
| 위반 | 검출 위치 | HTTP | code |
|---|---|---|---|
| 본문 JSON 파싱 실패 / 타입 불일치 / 본문 누락 | Jackson | 400 | VALIDATION_ERROR |
| product `name` 공백·100자 초과, `price` ∉ [1, 10⁷], `stock` ∉ [0, 10⁶] (DB `ck_products_price`·`ck_products_stock` 대응) | Bean Validation | 400 | VALIDATION_ERROR |
| coupon `code` 패턴(`ck_coupons_code`), `type`(`ck_coupons_type`), `value<1` 또는 RATE `>100`(`ck_coupons_value`), `minOrderAmount<0`, `maxDiscountAmount<1`, `totalQuantity<1`, `validFrom ≥ validUntil`(`ck_coupons_valid_range`) | Bean Validation / 교차 검증 | 400 | VALIDATION_ERROR |
| `X-User-Id`/`Idempotency-Key` 누락·공백·길이 초과 | Spring 헤더 검증 | 400 | VALIDATION_ERROR |
| `items` 0개·21개 이상, null 원소, `quantity` ∉ [1,1000](`ck_order_items_quantity`), productId 중복(`uk_order_items_order_product`) | Bean Validation / 교차 검증 | 400 | VALIDATION_ERROR |
| `cardToken` 공백 | Bean Validation | 400 | VALIDATION_ERROR |
| path id Long 변환 불가 | `MethodArgumentTypeMismatchException` | 400 | VALIDATION_ERROR |
| 목록 `status` 미정의, `size` ∉ [1,100] 또는 비정수, `userId` 공백·50자 초과, `cursor` 해석 불가 | 변환·메서드 검증·커서 디코더 | 400 | VALIDATION_ERROR |
| 같은 scope·키, 다른 지문 (`uk_idempotency_scope_key` 충돌 + hash 불일치) | `IdempotencyService.begin` | 422 | IDEMPOTENCY_KEY_MISMATCH |
| 같은 scope·키, 같은 지문, `IN_PROGRESS` | `IdempotencyService.begin` | 409 | IDEMPOTENCY_IN_PROGRESS |
| 같은 scope·키, 같은 지문, `COMPLETED` | `IdempotencyService.begin` | 저장된 2xx 재생 | — |
| 상품 없음 (`fk_order_items_product`가 막기 전에 서비스가 검출) | 업무 트랜잭션 | 404 | PRODUCT_NOT_FOUND |
| 쿠폰 없음 (`fk_orders_coupon_code` 대응) | 업무 트랜잭션 | 404 | COUPON_NOT_FOUND |
| 주문 없음 | 업무 트랜잭션 | 404 | ORDER_NOT_FOUND |
| `stock - reserved < quantity` (`ck_products_reserved`의 사전 검사) | 상품 락 하 검사 | 409 | INSUFFICIENT_STOCK |
| 쿠폰 기간 밖 / `subtotal < minOrderAmount` / 같은 사용자 사용 중 (`uk_orders_coupon_user_active` 사전 검사 + 안전망) | 쿠폰 락 하 검사 / DB 유니크 | 409 | COUPON_NOT_APPLICABLE |
| `usedCount ≥ totalQuantity` (`ck_coupons_used_count`의 사전 검사) | 쿠폰 락 하 검사 | 409 | COUPON_EXHAUSTED |
| 쿠폰 code 중복 (`uk_coupons_code`) | `existsByCode` + DB 유니크 | 409 | DUPLICATE_COUPON_CODE |
| 주문 상태 전이 불가 / 결제 시 만료 (`ck_orders_status` 집합 내 전이 규칙) | 주문 락 하 검사 | 409 | INVALID_STATE |
| PG `DECLINED` | PG 응답 | 402 | PAYMENT_DECLINED |
| PG 5xx·연결 실패·2초 초과·계약 밖 응답 | PG 클라이언트 | 503 | PAYMENT_GATEWAY_UNAVAILABLE |
| 그 밖의 CHECK 위반(`ck_products_reserved`, `ck_coupons_used_count`, `ck_orders_amounts` 등이 실제로 터짐) | DB | 500 | (버그 — 사전 검사가 누락된 것) |
