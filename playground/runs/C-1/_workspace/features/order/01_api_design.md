# 01 — API 설계: 주문(Order) + 재고 차감

- 스택: Java 21 / Spring Boot 3.5.x (Spring 6.2) / JPA(Hibernate 6.6) + PostgreSQL / Flyway
- 데이터 모델: [`02_db_design.md`](./02_db_design.md)
- 정합 기준표: 이 문서 맨 끝 **§9 정합 요약**. 구현자(backend-impl)와 검증자(boundary-verifier)는 이 표 하나만 기준으로 삼는다.

## 0. 기존 관례

- 빌드: `build.gradle`(Groovy DSL), 베이스 패키지 `com.example.order`(`OrderApplication`). 도메인 코드·에러 포맷 관례는 아직 없음 → 스킬 기본값(기능별 패키지, record DTO, RFC 9457)을 채택한다.
- 권장 패키지(기능별):
  - `com.example.order.product` — `Product`, `ProductRepository`, `ProductService`, `ProductController`, `ProductNotFoundException`, `dto/`
  - `com.example.order.order` — `Order`, `OrderItem`, `OrderStatus`, `OrderRepository`, `OrderService`, `OrderController`, 도메인 예외, `dto/`
  - `com.example.order.common.error` — `GlobalExceptionHandler`, `ProblemTypes`(type URI 상수)
  - `com.example.order.common.web` — `PageResponse<T>`

## 1. 엔드포인트 목록

| ID | 메서드 | 경로 | 성공 | 본문 | 헤더 | 에러 |
|---|---|---|---|---|---|---|
| R1 | POST | `/api/products` | 201 | `ProductResponse` | `Location: {base}/api/products/{id}` | 400 |
| R2 | GET | `/api/products/{id}` | 200 | `ProductResponse` | — | 400(id 형식), 404 |
| R3 | POST | `/api/orders` | 201 | `OrderResponse` (status=`ORDERED`) | `Location: {base}/api/orders/{id}` | 400, 404, 409 |
| R4 | GET | `/api/orders/{id}` | 200 | `OrderResponse` | — | 400(id 형식), 404 |
| R5 | POST | `/api/orders/{id}/cancel` | 200 | `OrderResponse` (status=`CANCELLED`) | — | 400(id 형식), 404, 409 |
| R6 | GET | `/api/orders?page={page}&size={size}` | 200 | `PageResponse<OrderResponse>` | — | 400 |

- 요청 본문 `Content-Type: application/json`. 성공 응답 `Content-Type: application/json`.
- 에러 응답은 전부 `Content-Type: application/problem+json` (§6).
- `Location`은 `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`로 만든 **절대 URI**(예: `http://localhost:8080/api/orders/7`). 테스트는 `endsWith("/api/orders/" + id)`로 검증한다. [Q.] 상대 경로를 원하면 알려 달라 — 기본안은 절대 URI.
- 인증·인가 없음(범위 제외).

## 2. 공통 타입 규약

| 항목 | 규약 |
|---|---|
| ID | JSON number(Java `Long`, DB `bigint`). 경로 변수 `{id}`는 `Long`으로 바인딩한다. |
| 금액 `price`·`unitPrice`·`totalPrice` | JSON 정수(Java `long`, DB `bigint`). 원 단위. 소수(`1.5`, `1.0`)나 문자열(`"1000"`)은 **400** (§5.4 Jackson 설정). |
| 수량 `stock`·`quantity` | JSON 정수(Java `int`, DB `integer`). int 범위를 넘으면 JSON 파싱 실패로 400. |
| `createdAt` | ISO-8601 UTC 문자열. Java `Instant` → Jackson 기본 직렬화(`WRITE_DATES_AS_TIMESTAMPS`는 Boot 기본값 false) → 예 `"2026-10-09T03:12:45.123456Z"`. 생성 시 `Instant.now().truncatedTo(ChronoUnit.MICROS)`로 PostgreSQL `timestamptz` 정밀도(µs)에 맞춘다. 맞추지 않으면 POST 응답(ns)과 GET 응답(µs)의 `createdAt`이 달라진다. |
| `status` | 문자열 enum: `"ORDERED"` \| `"CANCELLED"` |
| null 필드 | 성공 응답에는 null 필드가 없다(모든 필드 non-null). |
| 알 수 없는 요청 필드 | 무시한다(Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`). [Q.] 400으로 거절하려면 알려 달라 — 기본안은 무시. |

## 3. 스키마

### 3.1 상품

**`CreateProductRequest`** (R1 요청)

| 필드 | JSON 타입 | Java 타입 | 필수 | 검증(Bean Validation) | 위반 시 |
|---|---|---|---|---|---|
| `name` | string | `String` | Y | `@NotBlank`, `@Size(max = 255)` | 400 `validation-error` |
| `price` | integer | `Long` | Y | `@NotNull`, `@Positive` (> 0) | 400 `validation-error` |
| `stock` | integer | `Integer` | Y | `@NotNull`, `@PositiveOrZero` (≥ 0) | 400 `validation-error` |

- `@NotBlank`는 `null`, `""`, `"   "`(공백·탭·개행만 있는 문자열)을 모두 거절한다.
- [Q.] `name` 최대 길이는 요구사항에 없다. 기본안은 255자(`varchar(255)`)이며, 넘으면 400이다. 제한을 두지 않으면 긴 이름이 DB 오류(500)로 이어진다.
- [Q.] `name` 앞뒤 공백은 trim하지 않고 그대로 저장한다(기본안).

**`ProductResponse`** (R1 응답 본문, R2 응답)

```json
{ "id": 1, "name": "키보드", "price": 35000, "stock": 10 }
```

| 필드 | JSON 타입 | Java 타입 | nullable |
|---|---|---|---|
| `id` | integer | `Long` | N |
| `name` | string | `String` | N |
| `price` | integer | `long` | N |
| `stock` | integer | `int` | N (조회 시점의 현재 재고) |

### 3.2 주문

**`CreateOrderRequest`** (R3 요청)

```json
{ "items": [ { "productId": 1, "quantity": 2 }, { "productId": 3, "quantity": 1 } ] }
```

| 필드 | JSON 타입 | Java 타입 | 필수 | 검증 | 위반 시 |
|---|---|---|---|---|---|
| `items` | array | `List<@NotNull @Valid OrderItemRequest>` | Y | `@NotEmpty`(null·빈 배열 거절), `@DistinctProductIds`(커스텀, §5.2) | 400 `validation-error` |
| `items[].productId` | integer | `Long` | Y | `@NotNull` | 400 `validation-error` |
| `items[].quantity` | integer | `Integer` | Y | `@NotNull`, `@Min(1)` | 400 `validation-error` |

- 배열 원소 `null`(`"items":[null]`)은 원소 타입의 `@NotNull` 때문에 400이다.
- [Q.] `productId`가 0 이하이면 400으로 볼지 404로 볼지가 요구사항에 정해져 있지 않다. 기본안: **형식만 검사(`@NotNull`)하고 범위는 검사하지 않는다.** 0·음수·존재하지 않는 값은 모두 "없는 상품"으로 보고 **404**를 낸다. 경로 변수 `{id}`도 같은 규칙을 따른다(`/api/orders/-1` → 404, `/api/orders/abc` → 400).
- 상한 검사는 없다. 수량이 재고보다 크면 409(재고 부족)이다.

**`OrderResponse`** (R3 응답 본문, R4, R5, R6 `content[]` 원소)

```json
{
  "id": 7,
  "status": "ORDERED",
  "totalPrice": 105000,
  "items": [
    { "productId": 1, "quantity": 2, "unitPrice": 35000 },
    { "productId": 3, "quantity": 1, "unitPrice": 35000 }
  ],
  "createdAt": "2026-10-09T03:12:45.123456Z"
}
```

| 필드 | JSON 타입 | Java 타입 | nullable | 의미 |
|---|---|---|---|---|
| `id` | integer | `Long` | N | 주문 ID |
| `status` | string | `OrderStatus` (enum) | N | `ORDERED` / `CANCELLED` |
| `totalPrice` | integer | `long` | N | 생성 시 Σ(unitPrice × quantity)를 계산해 저장한 값 |
| `items` | array | `List<OrderItemResponse>` | N (원소 1개 이상) | 요청 순서를 유지한다(§3.3) |
| `items[].productId` | integer | `Long` | N | |
| `items[].quantity` | integer | `int` | N | |
| `items[].unitPrice` | integer | `long` | N | **주문 시점** 상품 가격 스냅숏 |
| `createdAt` | string(ISO-8601) | `Instant` | N | 생성 시각(UTC) |

- [Q.] 취소 후에도 `totalPrice`·`items`·`createdAt`은 바꾸지 않고 `status`만 `CANCELLED`로 바꾼다(기본안). 취소 시각(`cancelledAt`)은 요구사항에 없으므로 넣지 않는다.

### 3.3 items 순서

- 응답 `items`는 **요청 배열 순서**를 유지한다. 구현 방식: 요청 순서대로 `OrderItem`을 persist하면 IDENTITY id가 오름차순이 되고, 조회할 때 `@OrderBy("id ASC")`로 읽는다.
- 재고 차감 UPDATE는 데드락 회피를 위해 **productId 오름차순**으로 실행한다(§7). 응답 순서와는 무관하다.
- [Q.] 응답 items를 productId 오름차순으로 낼지 — 기본안은 요청 순서.

### 3.4 페이지 응답

**`PageResponse<T>`** (R6) — Spring `Page`를 직접 직렬화하지 않는 전용 record

```json
{ "content": [ { ...OrderResponse } ], "page": 0, "size": 20, "totalElements": 42 }
```

| 필드 | JSON 타입 | Java 타입 | 의미 |
|---|---|---|---|
| `content` | array | `List<T>` | 이 페이지의 주문들(`OrderResponse`). 범위를 넘는 페이지면 `[]` |
| `page` | integer | `int` | 요청한 page(기본값이 적용된 값) |
| `size` | integer | `int` | 요청한 size(기본값이 적용된 값) |
| `totalElements` | integer | `long` | 전체 주문 수 |

- 정렬: `createdAt DESC, id DESC`로 고정한다(`Sort.by(desc("createdAt"), desc("id"))`). 클라이언트가 정렬을 지정할 수 없다(`sort` 파라미터는 무시).
- 페이지 범위를 넘는 요청(`page=1000`)은 200 + `content: []` + 실제 `totalElements`를 반환한다.

## 4. 페이지네이션 파라미터 검증 (R6)

| 파라미터 | 기본값 | 허용 범위 | 바인딩 | 위반 시 |
|---|---|---|---|---|
| `page` | 0 | ≥ 0 | `@RequestParam(name = "page", defaultValue = "0") @Min(0) int page` | 400 `validation-error` |
| `size` | 20 | 1 ~ 100 | `@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size` | 400 `validation-error` |
| 숫자가 아닌 값(`page=abc`), int 범위 초과 | — | — | `MethodArgumentTypeMismatchException` | 400 `invalid-parameter` |

- **`Pageable` 인자 리졸버를 쓰지 않는다.** Spring Data의 `PageableHandlerMethodArgumentResolver`는 범위를 벗어난 값을 400으로 거절하지 않고 조용히 보정한다(음수 page → 0, 큰 size → max). 그래서 요구사항 "범위 밖이면 400"을 지킬 수 없다.
- 컨트롤러 클래스에 `@Validated`를 **붙이지 않는다.** Spring 6.1+의 내장 메서드 검증이 동작해 `HandlerMethodValidationException`이 발생한다(`ResponseEntityExceptionHandler`가 400으로 처리). 실수로 `@Validated`를 붙이면 `ConstraintViolationException`이 대신 발생하므로, 핸들러는 방어적으로 이 예외도 400 `validation-error`로 매핑한다.
- `?page=`(빈 값)은 `defaultValue`가 적용된다.

## 5. 검증 규칙과 오류 우선순위 (400 → 404 → 409)

### 5.1 보장 방식 — 계층 순서로 강제

모든 400 판정은 **서비스에 들어가기 전**(웹 계층)에 끝나고, 서비스는 DB 상태가 필요한 판정만 404 → 409 순서로 한다. 그래서 한 요청에 오류가 여럿이어도 앞 단계가 먼저 반환된다.

```
[1] 웹 계층 — DB 접근 없음 → 400
    a. 경로/쿼리 변수 타입 변환 실패 (/api/orders/abc, page=abc)  → invalid-parameter
    b. 본문 JSON 파싱 실패·본문 없음·타입 불일치(문자열/소수→정수)   → malformed-request
    c. Bean Validation (@Valid @RequestBody, @Min/@Max on @RequestParam)
       - items null/빈 배열, 원소 null, productId null, quantity null/<1
       - productId 중복(@DistinctProductIds)
       - name blank/255 초과, price null/≤0, stock null/<0
       - page < 0, size ∉ [1,100]                               → validation-error
       (a·b가 발생하면 c는 실행되지 않음 — Spring의 바인딩 순서)
[2] 서비스 — 존재 확인 → 404
    - 요청의 모든 productId를 한 번에 조회하고, 하나라도 없으면 ProductNotFoundException
    - 주문 조회·취소의 주문 없음 → OrderNotFoundException
[3] 서비스 — 상태/재고 → 409
    - 재고 부족(조건부 UPDATE 영향 행 0) → InsufficientStockException
    - 이미 취소된 주문 → OrderAlreadyCancelledException
```

- **R3 핵심 순서:** 모든 항목의 존재 확인([2])을 **어떤 재고 차감([3])보다도 먼저** 전부 마친다. 예: `[{productId: 1(재고 부족)}, {productId: 999(없음)}]` → 404. 항목별로 "존재 확인 → 차감"을 번갈아 하면 첫 항목의 409가 먼저 나가므로 금지한다.
- 400 검증 오류가 여러 개면 **한 번의 400 응답**에 모아 `errors` 확장 필드로 전부 보고한다.

### 5.2 productId 중복 검사 — Bean Validation 단계에서 처리

- 커스텀 제약 `@DistinctProductIds`를 `CreateOrderRequest.items` 필드에 붙인다. `ConstraintValidator<DistinctProductIds, List<OrderItemRequest>>`.
- 검증기는 `null` 리스트와 `null` 원소, `null` productId를 **건너뛴다**(true 반환). 이런 값은 `@NotEmpty`/`@NotNull`이 따로 보고하므로 중복 보고를 피한다.
- 위반하면 `errors[].field = "items"`이다.
- 서비스 단계에서 중복을 검사하는 방식은 쓰지 않는다. 400이 서비스 안에서 나오면 우선순위를 보장하는 근거가 "계층"에서 "코드 순서"로 약해진다.

### 5.3 금액 오버플로

- `totalPrice`는 `Math.multiplyExact`/`Math.addExact`로 계산한다. 오버플로(`ArithmeticException`)가 나면 `AmountOverflowException` → **400 `amount-overflow`**.
- 이 판정은 가격이 필요하므로 [2](404) 다음, [3](409, 재고 UPDATE) 전에 실행된다. 즉 "404 → 400(overflow) → 409" 순서인 유일한 예외 경로다.
- [Q.] 요구사항에 없는 극단 케이스(가격 × 수량이 2^63을 넘는 경우)다. 기본안은 위와 같다. 가격 상한을 두는 방안도 있지만 요구사항에 없는 400 규칙을 R1에 추가하게 되므로 택하지 않았다.

### 5.4 Jackson 엄격화 (금액·수량이 정수임을 보장)

`application.yml`(전문은 [02 §5](./02_db_design.md#5-설정-applicationyml)):

```yaml
spring:
  jackson:
    deserialization:
      accept-float-as-int: false        # 1.5 → 1 로 조용히 잘리는 것 방지 → 400
    mapper:
      allow-coercion-of-scalars: false  # "1000"(문자열) → 1000 강제 변환 방지 → 400
```

이 설정에 따른 파싱 실패는 모두 `HttpMessageNotReadableException` → 400 `malformed-request`이다.

## 6. 에러 응답 (RFC 9457 ProblemDetail, R8)

### 6.1 형식

- `Content-Type: application/problem+json`, 최소 필드는 `type`·`title`·`status`·`detail`. Spring이 `instance`(요청 경로)를 덧붙일 수 있으며 허용한다.
- **Content-Type 고정:** 핸들러는 `ResponseEntity<ProblemDetail>`(또는 `ResponseEntityExceptionHandler`의 `handleExceptionInternal`/`createResponseEntity` 오버라이드)에서 `contentType(MediaType.APPLICATION_PROBLEM_JSON)`를 **명시**한다. 그래야 클라이언트가 `Accept: application/json`만 보내도 협상 결과와 무관하게 `application/problem+json`으로 나간다.
- 500에는 예외 메시지·스택트레이스·SQL을 넣지 않는다(로그에만 남긴다).

```json
{
  "type": "https://example.com/problems/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "재고가 부족합니다: productId=1, 요청 수량=3",
  "instance": "/api/orders",
  "productId": 1
}
```

검증 실패 예:

```json
{
  "type": "https://example.com/problems/validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "요청 검증에 실패했습니다.",
  "instance": "/api/orders",
  "errors": [
    { "field": "items[0].quantity", "message": "1 이상이어야 합니다" },
    { "field": "items", "message": "productId는 중복될 수 없습니다" }
  ]
}
```

### 6.2 Problem type 목록

type URI 접두사: `https://example.com/problems/` (상수 클래스 `ProblemTypes` 한 곳에 정의)

| slug | status | title | 발생 원인(예외) | 확장 필드 |
|---|---|---|---|---|
| `validation-error` | 400 | Validation Failed | `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | `errors: [{field, message}]` |
| `malformed-request` | 400 | Malformed Request Body | `HttpMessageNotReadableException` (JSON 문법 오류, 본문 없음, 타입 불일치, 소수/문자열 → 정수) | — (Jackson 내부 메시지 노출 금지, detail은 고정 문구) |
| `invalid-parameter` | 400 | Invalid Parameter | `MethodArgumentTypeMismatchException` / `TypeMismatchException` (`/api/orders/abc`, `page=abc`) | `parameter: "<이름>"` |
| `amount-overflow` | 400 | Amount Overflow | `AmountOverflowException` (§5.3) | — |
| `product-not-found` | 404 | Product Not Found | `ProductNotFoundException` (R2, R3) | `productIds: [..]` (R3에서 없는 id 전부, 오름차순) |
| `order-not-found` | 404 | Order Not Found | `OrderNotFoundException` (R4, R5) | — |
| `insufficient-stock` | 409 | Insufficient Stock | `InsufficientStockException` (R3, R7) | `productId` (처음 실패한 항목, productId 오름차순 기준) |
| `order-already-cancelled` | 409 | Order Already Cancelled | `OrderAlreadyCancelledException` (R5) | — |
| `internal-error` | 500 | Internal Server Error | 그 외 `Exception` | — |

Spring 기본 처리에 맡기는 것(요구사항 범위 밖이며 `type`은 `about:blank`)도 problem+json으로 나간다. `ResponseEntityExceptionHandler`를 상속하면 다음이 자동으로 처리된다.
- 매핑이 없는 경로 → 404 (`NoResourceFoundException`)
- 메서드 불일치(`GET /api/orders/1/cancel`) → 405
- JSON이 아닌 Content-Type → 415

### 6.3 핸들러 구조

- `@RestControllerAdvice class GlobalExceptionHandler extends ResponseEntityExceptionHandler`
  - 오버라이드: `handleMethodArgumentNotValid`, `handleHandlerMethodValidationException`, `handleHttpMessageNotReadable`, `handleTypeMismatch`. 각각 위 표의 type/title로 바꾼다.
  - `@ExceptionHandler`: `ConstraintViolationException`, 도메인 예외 5종, `Exception`(500).
- 도메인 예외는 모두 `RuntimeException`이며 HTTP를 모른다. RuntimeException이어야 `@Transactional`이 롤백한다(R3 원자성의 전제).

## 7. 트랜잭션·동시성 설계 (R3 원자성, R5, R7)

### 7.1 주문 생성 (R3, R7) — 단일 트랜잭션 + 조건부 원자 UPDATE

`OrderService.create` (`@Transactional`, 격리 수준은 기본 READ COMMITTED):

```
1. ids = items의 productId 목록 (400 검증을 통과했으므로 null·중복 없음)
2. products = productRepository.findAllById(ids)            -- 일반 SELECT, 락 없음
   missing = ids − 찾은 id → 비어 있지 않으면 throw ProductNotFoundException(missing 오름차순)   [404]
3. 각 item: unitPrice = product.price (스냅숏)
   totalPrice = Σ multiplyExact(unitPrice, quantity) (addExact) → 오버플로면 AmountOverflowException  [400]
4. items를 productId 오름차순으로 정렬해 차례로:
     n = productRepository.decreaseStock(productId, quantity)
         -- UPDATE products SET stock = stock - :q WHERE id = :id AND stock >= :q
     n == 0 → throw InsufficientStockException(productId, quantity)                            [409]
            → RuntimeException이므로 트랜잭션 전체 롤백: 앞서 차감한 항목도 원복됨(R3 원자성)
5. Order(status=ORDERED, totalPrice, createdAt=now µs) + OrderItem(요청 순서, unitPrice 스냅숏) persist
6. OrderResponse 반환 → 커밋
```

**선택 근거:**
- **정확성(R7):** PostgreSQL READ COMMITTED에서 같은 행을 UPDATE하는 트랜잭션들은 행 락에서 직렬화된다. 대기하던 UPDATE는 선행 트랜잭션이 커밋한 **최신 행 버전으로 WHERE(`stock >= :q`)를 다시 평가**한다(EvalPlanQual). 그래서 재고 10에 수량 1 주문 20건을 동시에 보내면 정확히 10건이 1행을 갱신하고(201), 10건은 0행이 되어(409) 최종 재고는 0이 된다. 읽고 → 판단하고 → 쓰는 사이에 틈이 없으므로 lost update가 생길 수 없다.
- **원자성(R3):** 모든 UPDATE와 INSERT가 한 트랜잭션 안에 있다. 어느 항목이든 409가 나면 예외 전파로 전체가 롤백된다.
- **데드락 회피:** 여러 상품을 담은 주문들이 행 락을 서로 다른 순서로 잡지 않도록, UPDATE를 항상 **productId 오름차순**으로 실행한다. 취소의 재고 복원(§7.2)도 같은 순서를 쓴다.
- **404 → 409 순서:** 2단계(존재 확인)가 4단계(차감)보다 먼저 전부 끝난다. 상품 삭제 API가 없으므로(범위 제외) 2단계에서 확인한 존재가 4단계까지 유효하다.
- **가격 스냅숏:** 상품 수정 API가 없으므로(범위 제외) 2단계에서 읽은 가격이 곧 주문 시점 가격이다. 나중에 가격 수정이 생기면 `UPDATE ... RETURNING price`(native)로 차감과 가격 읽기를 원자화하도록 바꾼다.
- **채택하지 않은 대안:**
  - 낙관적 락(`@Version`) — 재시도 없이는 충돌한 요청이 실패하므로 "정확히 10건 성공"을 보장하지 못한다.
  - 비관적 락(`SELECT ... ORDER BY id FOR UPDATE` 후 메모리에서 차감)도 올바른 방식이다. 다만 조건부 UPDATE가 왕복 수가 같거나 적고, 엔티티 상태와 DB 상태가 어긋날 여지가 없어 이쪽을 택했다.
  - SERIALIZABLE 격리 — 직렬화 실패가 500으로 새므로 쓰지 않는다.
- **구현 주의:**
  - `decreaseStock`은 `@Modifying @Query("update Product p set p.stock = p.stock - :quantity where p.id = :id and p.stock >= :quantity")`로 정의하고 반환 타입은 `int`이다.
  - 2단계에서 로드한 `Product` 엔티티는 **수정하지 않는다**. 벌크 UPDATE는 영속성 컨텍스트를 거치지 않으므로, 엔티티의 stock을 건드리면 dirty checking이 오래된 값으로 덮어쓴다.
  - 동시성 테스트(R7)는 테스트 메서드에 `@Transactional`을 붙이지 않는다. 실제 HTTP(`webEnvironment = RANDOM_PORT`) 또는 서비스를 여러 스레드에서 호출해야 한다. Hikari 기본 풀(10)로 20건을 처리할 수 있다. 락 보유자도 커넥션을 쥐고 있으므로 진행이 보장된다.

### 7.2 주문 취소 (R5) — 주문 행 비관적 락 + 원자 증가 UPDATE

`OrderService.cancel` (`@Transactional`):

```
1. order = orderRepository.findByIdForUpdate(id)      -- @Lock(PESSIMISTIC_WRITE): SELECT ... FOR UPDATE
   없음 → throw OrderNotFoundException                                                       [404]
2. order.cancel(): status == CANCELLED → throw OrderAlreadyCancelledException                 [409]
                   아니면 status = CANCELLED (dirty checking으로 UPDATE)
3. order.items를 productId 오름차순으로 정렬해:
     productRepository.increaseStock(productId, quantity)
       -- UPDATE products SET stock = stock + :q WHERE id = :id
4. OrderResponse 반환 → 커밋
```

**근거:** 같은 주문을 동시에 두 번 취소하면 두 번째 요청은 1단계 `FOR UPDATE`에서 첫 번째 요청의 커밋을 기다린다. 그 뒤 `CANCELLED`를 보고 409를 낸다. 따라서 재고 복원은 정확히 한 번만 일어난다. 재고 증가는 `stock = stock + :q` 원자 연산이라, 동시에 진행되는 주문 생성의 차감과 섞여도 값을 잃지 않는다. 락 순서는 주문 행 → 상품 행(오름차순)이다. 주문 생성은 기존 주문 행을 잠그지 않으므로 순환 대기가 생기지 않는다.

### 7.3 조회 (R2, R4, R6)

- `@Transactional(readOnly = true)`. `open-in-view: false` 환경이므로 DTO 변환은 서비스 트랜잭션 안에서 끝낸다.
- R4: `findById` 후 items 지연 로딩(트랜잭션 내). `@EntityGraph(attributePaths = "items")`를 써도 된다.
- R6: `orderRepository.findAll(PageRequest.of(page, size, Sort.by(desc("createdAt"), desc("id"))))`. items N+1은 `hibernate.default_batch_fetch_size: 100`(02 §5)으로 IN 배치 로딩해 막는다. **fetch join과 페이징을 함께 쓰지 않는다**(메모리 페이징 경고 HHH90003004).

## 8. 엔드포인트별 상세

### R1 POST /api/products
- 201, `Location: …/api/products/{id}`, 본문 `ProductResponse`(R2와 같은 형태).
- 400 `validation-error`: name null/blank/255자 초과, price null/≤ 0, stock null/< 0.
- 400 `malformed-request`: JSON 파싱 실패, 본문 없음, price `1.5`/`"100"`.

### R2 GET /api/products/{id}
- 200 `ProductResponse`.
- 400 `invalid-parameter`: `{id}`가 Long이 아님. 404 `product-not-found`: 없음.

### R3 POST /api/orders
- 201, `Location: …/api/orders/{id}`, 본문 `OrderResponse`(status=`ORDERED`, R4와 같은 형태).
- 400 `validation-error` / `malformed-request` (§5.1 [1]) → 404 `product-not-found` → (400 `amount-overflow`) → 409 `insufficient-stock`.
- 실패하면 어떤 상품의 재고도 바뀌지 않고, orders·order_items 행도 생기지 않는다.

### R4 GET /api/orders/{id}
- 200 `OrderResponse`. 400 `invalid-parameter`, 404 `order-not-found`.

### R5 POST /api/orders/{id}/cancel
- 요청 본문 없음(보내도 무시).
- 200 `OrderResponse`(status=`CANCELLED`). 재고는 각 item의 quantity만큼 복원된다.
- 400 `invalid-parameter` → 404 `order-not-found` → 409 `order-already-cancelled`(재고는 바뀌지 않음).

### R6 GET /api/orders?page&size
- 200 `PageResponse<OrderResponse>`, 정렬 `createdAt DESC, id DESC`.
- 400 `invalid-parameter`(숫자 아님) / `validation-error`(범위 밖).

---

## 9. 정합 요약 (구현·검증 기준표)

### 9(a). 응답·요청 필드 ↔ 컬럼 대응표

| API 필드 | 방향 | DTO 타입 | 엔티티 필드 | 컬럼 | 컬럼 타입 / NULL | 비고 |
|---|---|---|---|---|---|---|
| Product.`id` | 응답 | `Long` | `Product.id` | `products.id` | `bigint` identity PK / NOT NULL | |
| Product.`name` | 요청·응답 | `String` | `Product.name` | `products.name` | `varchar(255)` / NOT NULL, CHECK `btrim(name) <> ''` | 요청 `@NotBlank @Size(max=255)` |
| Product.`price` | 요청·응답 | `Long`(req) / `long`(res) | `Product.price` (`long`) | `products.price` | `bigint` / NOT NULL, CHECK `> 0` | 요청 `@NotNull @Positive` |
| Product.`stock` | 요청·응답 | `Integer`(req) / `int`(res) | `Product.stock` (`int`) | `products.stock` | `integer` / NOT NULL, CHECK `>= 0` | 요청 `@NotNull @PositiveOrZero` |
| Order.`id` | 응답 | `Long` | `Order.id` | `orders.id` | `bigint` identity PK / NOT NULL | |
| Order.`status` | 응답 | `OrderStatus` | `Order.status` (`@Enumerated(STRING)`) | `orders.status` | `varchar(20)` / NOT NULL, CHECK `IN ('ORDERED','CANCELLED')` | |
| Order.`totalPrice` | 응답 | `long` | `Order.totalPrice` (`long`, `updatable=false`) | `orders.total_price` | `bigint` / NOT NULL, CHECK `> 0` | 생성 시 Σ(unit_price × quantity)를 저장하며 이후 불변 |
| Order.`createdAt` | 응답 | `Instant` → ISO-8601 문자열 | `Order.createdAt` (`Instant`, `updatable=false`) | `orders.created_at` | `timestamptz` / NOT NULL | 앱에서 µs로 절삭해 설정 |
| Order.`items[]` | 응답 | `List<OrderItemResponse>` | `Order.items` (`@OneToMany(mappedBy="order")`, `@OrderBy("id ASC")`) | `order_items` (FK `order_id`) | — | 요청 순서 유지 |
| items[].`productId` | 요청·응답 | `Long` | `OrderItem.productId` (`Long`, 연관 아님) | `order_items.product_id` | `bigint` / NOT NULL, FK → products.id | 요청 `@NotNull` + `@DistinctProductIds` ↔ UNIQUE(order_id, product_id) |
| items[].`quantity` | 요청·응답 | `Integer`(req) / `int`(res) | `OrderItem.quantity` (`int`) | `order_items.quantity` | `integer` / NOT NULL, CHECK `>= 1` | 요청 `@NotNull @Min(1)` |
| items[].`unitPrice` | 응답 | `long` | `OrderItem.unitPrice` (`long`) | `order_items.unit_price` | `bigint` / NOT NULL, CHECK `> 0` | 주문 시점의 products.price 스냅숏 |
| (노출 안 함) | — | — | `OrderItem.id`, `OrderItem.order` | `order_items.id`, `order_items.order_id` | | 내부 키 |
| Page.`content`·`page`·`size`·`totalElements` | 응답 | `PageResponse<T>` | — | (컬럼 아님) | | `page`/`size`는 요청값, `totalElements` = `count(*) from orders` |

- 요청의 필수 필드는 모두 NOT NULL 컬럼에 대응하고, 응답 필드도 모두 non-null이다. 요청 필수성과 컬럼 nullable 사이에 어긋나는 곳이 없다.
- 요청 검증(Bean Validation)과 DB CHECK는 같은 규칙의 이중 방어다. 정상 경로에서 DB CHECK 위반은 일어나지 않으며, 일어나면 버그로 보고 500 처리한다.

### 9(b). 제약·규칙 위반 ↔ HTTP 상태코드 + ProblemDetail type 매핑표

type 접두사 `https://example.com/problems/`

| # | 규칙 / 제약 | 판정 위치 | 단계 | status | type (slug) | 대응 DB 제약(백스톱) |
|---|---|---|---|---|---|---|
| 1 | 경로 `{id}`·쿼리 `page`/`size` 타입 변환 실패 (`/api/orders/abc`) | Spring 바인딩 | [1] | 400 | `invalid-parameter` | — |
| 2 | 본문 JSON 파싱 실패, 본문 없음, 필드 타입 불일치, 소수/문자열 → 정수 | Jackson (`HttpMessageNotReadableException`) | [1] | 400 | `malformed-request` | — |
| 3 | `name` null/blank/> 255 | `@NotBlank @Size(max=255)` | [1] | 400 | `validation-error` | `ck_products_name_not_blank`, `varchar(255)` |
| 4 | `price` null / ≤ 0 | `@NotNull @Positive` | [1] | 400 | `validation-error` | `ck_products_price_positive` |
| 5 | `stock` null / < 0 | `@NotNull @PositiveOrZero` | [1] | 400 | `validation-error` | `ck_products_stock_non_negative` |
| 6 | `items` null / 빈 배열 / 원소 null | `@NotEmpty`, `List<@NotNull …>` | [1] | 400 | `validation-error` | — |
| 7 | `items[].productId` null | `@NotNull` | [1] | 400 | `validation-error` | `order_items.product_id NOT NULL` |
| 8 | `items[].quantity` null / < 1 | `@NotNull @Min(1)` | [1] | 400 | `validation-error` | `ck_order_items_quantity_positive` |
| 9 | 같은 productId 중복 | `@DistinctProductIds` | [1] | 400 | `validation-error` | `uq_order_items_order_product` |
| 10 | `page` < 0, `size` ∉ [1,100] | `@Min`/`@Max` on `@RequestParam` (`HandlerMethodValidationException`) | [1] | 400 | `validation-error` | — |
| 11 | 상품 없음 (R2, R3: 하나라도) | 서비스 `ProductNotFoundException` | [2] | 404 | `product-not-found` | `fk_order_items_product` |
| 12 | 주문 없음 (R4, R5) | 서비스 `OrderNotFoundException` | [2] | 404 | `order-not-found` | — |
| 13 | totalPrice long 오버플로 | 서비스 `AmountOverflowException` | [2]와 [3] 사이 | 400 | `amount-overflow` | `bigint` 범위 |
| 14 | 재고 부족 (R3, R7) | 조건부 UPDATE 영향 행 0 → `InsufficientStockException` → 롤백 | [3] | 409 | `insufficient-stock` | `ck_products_stock_non_negative` |
| 15 | 이미 취소된 주문 (R5) | `FOR UPDATE` 후 `Order.cancel()` → `OrderAlreadyCancelledException` | [3] | 409 | `order-already-cancelled` | `ck_orders_status` |
| 16 | 그 외 예외 | `@ExceptionHandler(Exception.class)` | — | 500 | `internal-error` | — |

모든 에러는 `Content-Type: application/problem+json`이고 `type`·`title`·`status`·`detail`을 포함한다(R8).
