# 01. API 설계 — order (주문 + 재고 차감)

- 스택: Java 21 / Spring Boot 3.5.16 (web, validation, data-jpa) / JPA + Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers
- 근거: `feature.md`, `00_requirements.json` (R1~R8)
- 짝 문서: [02_db_design.md](./02_db_design.md) — 테이블·제약·마이그레이션·동시성 전략
- 기존 관례: `com.example.order.OrderApplication` 진입점만 있고 기존 레이어링·에러 포맷 관례는 없다. 따라서 spring-boot 스킬 기본값(기능별 패키지, record DTO, RFC 9457 ProblemDetail)을 새 기준으로 둔다.

---

## 1. 공통 규약

| 항목 | 규약 |
|---|---|
| Base path | `/api` |
| 요청/응답 Content-Type | 성공 시 `application/json`. 에러는 항상 `application/problem+json` |
| 필드 네이밍 | camelCase (Jackson 기본) |
| 금액 | `price`, `unitPrice`, `totalPrice`는 JSON 정수. Java `long`(DB `BIGINT`) |
| 수량/재고 | `quantity`, `stock`은 JSON 정수. Java `int`(DB `INTEGER`) |
| 시각 | `createdAt`은 ISO-8601 UTC 문자열, 예: `"2026-10-09T03:12:45.123456Z"` (§1.2) |
| 주문 상태 | `"ORDERED"` \| `"CANCELLED"` |
| id | JSON 정수(`long`). DB identity가 발급 |
| 알 수 없는 JSON 필드 | 무시한다 (Spring Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false` 유지) |

### 1.1 숫자 입력 엄격성
- 정수 필드에 소수(`1.5`)가 오면 **400**이어야 한다. Jackson 기본값은 소수를 정수로 잘라 받으므로(`ACCEPT_FLOAT_AS_INT=true`) 다음 설정으로 끈다:
  `spring.jackson.deserialization.accept-float-as-int: false` → `HttpMessageNotReadableException` → 400 (§4.2 malformed-request).
- `int`/`long` 범위를 넘는 숫자(예: `stock: 99999999999`)는 Jackson이 역직렬화에 실패하므로 → 400 (malformed-request).
- 숫자 필드에 문자열(`"price": "100"`)이 오는 경우: `spring.jackson.mapper.allow-coercion-of-scalars: false`로 강제 변환을 꺼서 400으로 만든다(권장). 계약상 필수 조건은 아니므로 구현 시 이 설정이 다른 동작을 깨면 제거해도 된다.

### 1.2 createdAt 직렬화
- 엔티티·DTO 타입은 `java.time.Instant`. Jackson `JavaTimeModule`(Spring Boot 자동 등록) + `WRITE_DATES_AS_TIMESTAMPS=false`(Boot 기본값이며 yml에 명시) → `Instant.toString()` 형식의 ISO-8601(UTC, `Z` 접미사) 문자열.
- **마이크로초 절삭**: 생성 시 `Instant.now().truncatedTo(ChronoUnit.MICROS)`로 값을 정한다. PostgreSQL `timestamptz` 정밀도가 마이크로초이므로, 절삭하지 않으면 생성 응답(R3의 메모리 값, 나노초)과 이후 조회 응답(R4의 DB 값, 마이크로초)의 `createdAt`이 달라진다.

### 1.3 Location 헤더
- 생성(201) 응답은 **절대 URI**를 Location으로 준다: `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri()`
  - 상품: `http://{host}:{port}/api/products/{id}`
  - 주문: `http://{host}:{port}/api/orders/{id}`
- 테스트는 호스트/포트에 의존하지 말고 `endsWith("/api/products/" + id)` 형태로 검증한다.

---

## 2. 엔드포인트 목록

| ID | 메서드 | 경로 | 성공 | 에러 |
|---|---|---|---|---|
| R1 | POST | `/api/products` | 201 + Location, `ProductResponse` | 400 |
| R2 | GET | `/api/products/{id}` | 200, `ProductResponse` | 400(id 타입), 404 |
| R3 | POST | `/api/orders` | 201 + Location, `OrderResponse`(status=ORDERED) | 400, 404, 409 |
| R4 | GET | `/api/orders/{id}` | 200, `OrderResponse` | 400(id 타입), 404 |
| R5 | POST | `/api/orders/{id}/cancel` | 200, `OrderResponse`(status=CANCELLED) | 400(id 타입), 404, 409 |
| R6 | GET | `/api/orders?page={page}&size={size}` | 200, `OrderPageResponse` | 400 |

인증·인가 없음(범위 제외).

---

## 3. 엔드포인트 상세

### R1. 상품 등록 — `POST /api/products`

요청 `CreateProductRequest`:
```json
{ "name": "키보드", "price": 30000, "stock": 10 }
```

| 필드 | Java 타입 | 필수 | 검증 | 위반 |
|---|---|---|---|---|
| `name` | `String` | Y | `@NotBlank` (null·빈 문자열·공백만 불가) | 400 validation-error |
| `price` | `Long` | Y | `@NotNull @Positive` (> 0) | 400 validation-error |
| `stock` | `Integer` | Y | `@NotNull @PositiveOrZero` (≥ 0) | 400 validation-error |

- `name`은 받은 그대로 저장한다(trim 하지 않음). 길이 상한은 계약에 없으므로 두지 않는다(DB `TEXT`).
- 래퍼 타입(`Long`, `Integer`)을 써야 누락 필드가 0으로 채워지지 않고 `@NotNull`로 잡힌다.

응답 201, `Location: .../api/products/{id}`, 본문 `ProductResponse`(R2와 동일).

### R2. 상품 조회 — `GET /api/products/{id}`

응답 200 `ProductResponse`:
```json
{ "id": 1, "name": "키보드", "price": 30000, "stock": 10 }
```
- 없으면 404 product-not-found.
- `{id}`가 숫자가 아니면 400 invalid-parameter. 음수·0 등 숫자이지만 존재하지 않는 id는 404.

### R3. 주문 생성 — `POST /api/orders`

요청 `CreateOrderRequest`:
```json
{ "items": [ { "productId": 1, "quantity": 2 }, { "productId": 3, "quantity": 1 } ] }
```

| 필드 | Java 타입 | 필수 | 검증 | 위반 |
|---|---|---|---|---|
| `items` | `List<@NotNull @Valid OrderItemRequest>` | Y | `@NotEmpty` (null·빈 배열 불가, 원소 null 불가) | 400 |
| `items[].productId` | `Long` | Y | `@NotNull` | 400 |
| `items[].quantity` | `Integer` | Y | `@NotNull @Min(1)` | 400 |
| (요청 전체) | — | — | 같은 `productId` 중복 불가 — 레코드 메서드 `@AssertTrue @JsonIgnore boolean isProductIdsUnique()` (items가 null이거나 원소/productId가 null이면 `true`를 반환해 다른 제약에 맡김) | 400 |

- 중복 검사도 **Bean Validation 단계에 둔다**. 그래야 모든 400 판정이 DB 접근 전에 끝난다(§5 우선순위).
- `productId`의 양수 검증은 계약에 없으므로 두지 않는다. 존재하지 않는 id(음수 포함)는 404.

처리 규칙(서비스, 단일 트랜잭션 — 상세는 02 §4):
1. 요청의 모든 productId를 한 번에 조회한다(`findAllById`). 하나라도 없으면 → **404** product-not-found (detail에 없는 id 목록을 오름차순으로).
2. productId **오름차순**으로 항목마다 조건부 차감 `UPDATE products SET stock = stock - :q WHERE id = :id AND stock >= :q`. 영향받은 행이 0이면 → **409** insufficient-stock, 트랜잭션 롤백(앞서 차감한 항목도 원복).
3. `unitPrice` = 1단계에서 읽은 상품의 `price`(상품 수정 API가 없으므로 주문 시점 가격과 같다). `totalPrice` = Σ(unitPrice × quantity), `Math.multiplyExact`/`Math.addExact`로 계산한다.
4. `orders` 1행 + `order_items` N행 저장(status=ORDERED, createdAt=now를 마이크로초로 절삭).

응답 201, `Location: .../api/orders/{id}`, 본문 `OrderResponse`(status=`ORDERED`).

> 금액 오버플로(Σ가 `long` 범위를 넘는 경우)는 계약 밖의 극단 사례다. `ArithmeticException` → 400 validation-error("금액 합계가 표현 범위를 초과합니다")로 매핑하고 트랜잭션은 롤백한다. 이 400은 DB 조회 뒤에 판정되므로 400→404→409 우선순위의 예외 사례로 문서화만 하고, 필수 테스트 대상은 아니다.

### R4. 주문 조회 — `GET /api/orders/{id}`

응답 200 `OrderResponse`:
```json
{
  "id": 7,
  "status": "ORDERED",
  "totalPrice": 70000,
  "items": [
    { "productId": 1, "quantity": 2, "unitPrice": 30000 },
    { "productId": 3, "quantity": 1, "unitPrice": 10000 }
  ],
  "createdAt": "2026-10-09T03:12:45.123456Z"
}
```
- `items` 순서는 **요청에 넣은 순서**를 유지한다(`order_items.id` 오름차순 = 삽입 순서, `@OrderBy("id ASC")`).
- `totalPrice`는 생성 시 계산해 저장한 값이다. 불변식: `totalPrice == Σ(items.unitPrice × items.quantity)`. 취소해도 바뀌지 않는다.
- 없으면 404 order-not-found. `{id}`가 숫자가 아니면 400.

### R5. 주문 취소 — `POST /api/orders/{id}/cancel`

- 요청 본문 없음(보내도 무시).
- 처리(단일 트랜잭션 — 상세는 02 §5):
  1. 주문 존재 확인 → 없으면 **404** order-not-found.
  2. 조건부 전이 `UPDATE orders SET status='CANCELLED' WHERE id=:id AND status='ORDERED'`. 영향 행 0이면 → **409** order-already-cancelled.
  3. 주문 항목을 productId 오름차순으로 순회하며 `UPDATE products SET stock = stock + :q WHERE id = :pid`.
  4. 갱신된 주문을 다시 읽어 응답.
- 응답 200 `OrderResponse`(status=`CANCELLED`, 나머지 필드는 생성 시와 같음).

### R6. 주문 목록 — `GET /api/orders?page&size`

| 파라미터 | 타입 | 기본값 | 검증 | 위반 |
|---|---|---|---|---|
| `page` | `int` | 0 | `@Min(0)` | 400 validation-error |
| `size` | `int` | 20 | `@Min(1) @Max(100)` | 400 validation-error |

- 컨트롤러 시그니처:
  `list(@RequestParam(name = "page", defaultValue = "0") @Min(0) int page, @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size)`
- **Spring `Pageable` 인자 해석기를 쓰지 않는다.** 이 해석기는 범위를 벗어난 값을 조용히 보정(음수→0, 상한 초과→max)하므로 "범위 밖이면 400" 계약을 어긴다.
- **컨트롤러 클래스에 `@Validated`를 붙이지 않는다.** Spring 6.1+의 내장 메서드 검증이 `HandlerMethodValidationException`(400)을 던지게 한다. `@Validated`를 붙이면 AOP 경로로 `ConstraintViolationException`이 나와 별도 매핑이 필요해진다(안전망으로 핸들러에 매핑은 해 둔다, §4.3).
- 타입 불일치(`page=abc`, `size=1.5`, `int` 범위 초과) → `MethodArgumentTypeMismatchException` → 400 invalid-parameter.
- 빈 값(`?page=`)은 `defaultValue`가 적용된다(Spring 기본 동작).
- 정렬: `Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))` 고정. 클라이언트 정렬 파라미터는 받지 않는다.
- 범위를 벗어난 페이지(데이터보다 큰 page)는 200 + 빈 `content`.

응답 200 `OrderPageResponse`:
```json
{
  "content": [ { "id": 9, "status": "ORDERED", "totalPrice": 30000, "items": [ ... ], "createdAt": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 9
}
```
- `content` 원소는 `OrderResponse`와 같은 형태. `page`·`size`는 요청에 실제 적용된 값(기본값 적용 후)이다.
- Spring `Page`를 그대로 직렬화하지 말고 전용 record `PageResponse<T>(List<T> content, int page, int size, long totalElements)`로 감싼다. Spring Data 3.3+에서 `PageImpl` 직렬화는 경고 대상이고 필드 구성도 계약과 다르다.

---

## 4. 에러 응답 (R8 — RFC 9457)

### 4.1 형식
- `Content-Type: application/problem+json` (**모든** 에러 응답)
- 필수 필드: `type`, `title`, `status`, `detail`. `instance`(요청 경로)는 선택.
- 검증 실패(400 validation-error)에는 확장 필드 `errors: [{ "field": string, "message": string }]`를 붙인다.

```json
{
  "type": "https://example.com/problems/validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "요청 값이 유효하지 않습니다.",
  "instance": "/api/orders",
  "errors": [ { "field": "items[0].quantity", "message": "1 이상이어야 합니다" } ]
}
```
```json
{
  "type": "https://example.com/problems/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "재고가 부족합니다: productId=1, 요청 수량=3"
}
```

### 4.2 문제 유형 카탈로그

`type` 접두사: `https://example.com/problems/`. 상수는 `common/error/ProblemTypes`에 모은다.

| type 접미사 | status | title | 발생 원인(예외) | 해당 R |
|---|---|---|---|---|
| `validation-error` | 400 | Validation Failed | `MethodArgumentNotValidException`(@Valid 본문), `HandlerMethodValidationException`(page/size 범위), `ConstraintViolationException`(안전망), 금액 오버플로 `ArithmeticException` | R1, R3, R6 |
| `malformed-request` | 400 | Malformed Request | `HttpMessageNotReadableException` — JSON 문법 오류, 빈 본문, 본문 `null`, 타입 불일치(소수→정수, 범위 초과, 문자열→숫자) | R8(+R1, R3) |
| `invalid-parameter` | 400 | Invalid Parameter | `MethodArgumentTypeMismatchException`/`TypeMismatchException` — path `{id}`·query `page`/`size` 타입 불일치 | R2, R4, R5, R6 |
| `product-not-found` | 404 | Product Not Found | `ProductNotFoundException` | R2, R3 |
| `order-not-found` | 404 | Order Not Found | `OrderNotFoundException` | R4, R5 |
| `insufficient-stock` | 409 | Insufficient Stock | `InsufficientStockException` | R3, R7 |
| `order-already-cancelled` | 409 | Order Already Cancelled | `OrderAlreadyCancelledException` | R5 |
| `internal-error` | 500 | Internal Server Error | 그 밖의 `Exception`. detail은 고정 문구, 내부 메시지·SQL·스택트레이스 비노출 | — |
| (프레임워크 기본) `about:blank` | 404/405/415 등 | 상태 문구 | `NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException` 등 | — |

`detail`은 사람이 읽는 한국어 문장이다. Jackson 내부 메시지(`Cannot deserialize value of type...`)를 그대로 노출하지 않는다.

### 4.3 핸들러 구현 방침
- `common/error/GlobalExceptionHandler`: `@RestControllerAdvice` + `extends ResponseEntityExceptionHandler`. 그래야 MVC 표준 예외(본문 파싱 실패, 검증, 타입 불일치, 405, 415, 404 no-route)가 모두 ProblemDetail로 나온다.
  - 오버라이드: `handleHttpMessageNotReadable`, `handleMethodArgumentNotValid`, `handleHandlerMethodValidationException`, `handleTypeMismatch` → 위 카탈로그의 type/title/detail로 덮어쓴다.
  - **Content-Type 고정**: `createResponseEntity(...)`를 오버라이드해 `headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON)`을 강제하고, 도메인 예외용 `@ExceptionHandler`도 `ResponseEntity.status(..).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)`를 반환한다. 미리 지정된 Content-Type은 내용 협상을 건너뛰므로, 클라이언트가 `Accept: application/json`만 보내도 `application/problem+json`이 유지된다.
  - 도메인 예외 `@ExceptionHandler`: `ProductNotFoundException`, `OrderNotFoundException`(404), `InsufficientStockException`, `OrderAlreadyCancelledException`(409), `ConstraintViolationException`(400 안전망), `ArithmeticException`(400), `Exception`(500).
- `spring.mvc.problemdetails.enabled`는 켜지 않는다(자체 `ResponseEntityExceptionHandler` 빈이 있으면 의미가 없고, 설정만 헷갈리게 한다).
- 도메인 예외는 HTTP를 모르는 `RuntimeException` 하위 클래스다. `RuntimeException`이어야 `@Transactional`이 기본 규칙으로 롤백한다(R3 원자성).

---

## 5. 400 → 404 → 409 우선순위 보장

판정이 일어나는 **처리 단계 순서**로 보장한다. 앞 단계에서 오류가 나면 뒤 단계는 실행되지 않는다.

| 순서 | 단계 | 판정 | 결과 |
|---|---|---|---|
| ① | `DispatcherServlet` 인자 해석 — path/query 변환 | `{id}`·`page`·`size` 타입 불일치 | 400 |
| ② | `HttpMessageConverter` 본문 역직렬화 | JSON 문법 오류·빈 본문·타입 불일치 | 400 |
| ③ | `@Valid` Bean Validation / 메서드 검증 | 필수값·범위·items 비어있음·**productId 중복**·page/size 범위 | 400 |
| ④ | 서비스 진입 후 **첫 DB 접근** — 존재 확인 (`findAllById` / `existsById`) | 없는 상품·없는 주문 | 404 |
| ⑤ | 상태/재고 변경 (조건부 UPDATE) | 재고 부족·이미 취소 | 409 |

규칙:
- ①~③은 컨트롤러 메서드가 호출되기 전에 끝난다. 그래서 400은 DB 상태와 무관하게 항상 먼저 판정된다. 400 사유(중복 productId 포함)를 서비스 안에서 판정하지 않는다.
- R3: 요청 항목 **전체**의 존재 확인(④)을 끝낸 뒤에야 차감(⑤)을 시작한다. 예: 항목 A는 재고 부족이고 항목 B는 존재하지 않으면 404다.
- R5: 존재 확인(④)을 조건부 전이(⑤)보다 먼저 한다. 조건부 UPDATE만으로는 "없음"과 "이미 취소"를 구분할 수 없기 때문이다.
- 상품·주문은 삭제 API가 없다. 따라서 ④의 존재 판정은 이후 동시 요청이 와도 뒤집히지 않는다(경합 없음).

테스트 예시(우선순위 검증용):
- `items=[{productId:999, quantity:0}]` → 400 (404 아님)
- `items=[{productId:1,quantity:1},{productId:1,quantity:1}]`, 상품 1 없음 → 400
- `items=[{productId:재고0인상품,quantity:1},{productId:999,quantity:1}]` → 404 (409 아님), 재고 변화 없음

---

## 6. 패키지 구조 제안

기존 관례가 없으므로 스킬 기본값인 **기능별(by-feature)** 패키지를 쓴다. 베이스 패키지는 `com.example.order`(기존 `OrderApplication`의 패키지)다.

```
com.example.order
├── OrderApplication.java
├── product/
│   ├── Product.java                     # @Entity, products
│   ├── ProductRepository.java           # JpaRepository + decreaseStock/increaseStock (native @Modifying)
│   ├── ProductService.java              # R1, R2
│   ├── ProductController.java           # /api/products
│   ├── ProductNotFoundException.java    # 404 (단건 id 또는 id 목록)
│   ├── InsufficientStockException.java  # 409 (productId, requested)
│   └── dto/
│       ├── CreateProductRequest.java
│       └── ProductResponse.java
├── order/
│   ├── Order.java                       # @Entity, @Table(name="orders")
│   ├── OrderItem.java                   # @Entity, order_items
│   ├── OrderStatus.java                 # enum ORDERED, CANCELLED
│   ├── OrderRepository.java             # findWithItemsById(@EntityGraph), findAll(Pageable), cancelIfOrdered (native @Modifying)
│   ├── OrderService.java                # R3~R6 (ProductRepository 주입 — 같은 트랜잭션에서 재고 차감/복원)
│   ├── OrderController.java             # /api/orders
│   ├── OrderNotFoundException.java      # 404
│   ├── OrderAlreadyCancelledException.java # 409
│   └── dto/
│       ├── CreateOrderRequest.java      # + @AssertTrue isProductIdsUnique()
│       ├── OrderItemRequest.java
│       ├── OrderResponse.java
│       └── OrderItemResponse.java
└── common/
    ├── error/
    │   ├── GlobalExceptionHandler.java
    │   └── ProblemTypes.java
    └── web/
        └── PageResponse.java            # record PageResponse<T>(content, page, size, totalElements)
```

- 의존 방향: `web(Controller) → Service → Repository`. `OrderService`는 `ProductRepository`에 의존한다. 이 의존은 주문→상품 한 방향만 둔다(상품 패키지는 주문을 모른다).
- 엔티티→DTO 변환은 서비스 트랜잭션 안에서 끝낸다(`open-in-view=false`이므로 트랜잭션 밖 지연 로딩 금지).

---

## 7. 정합 요약 (구현·검증 기준표)

> 이 절이 필드↔컬럼, 제약↔상태코드의 **단일 기준**이다. `02_db_design.md`는 이 표를 복제하지 않고 링크로 참조한다.

### (a) 응답/요청 필드 ↔ 컬럼 대응표

| DTO 필드 | Java 타입 (DTO / 엔티티) | 테이블.컬럼 | DB 타입 | 요청 필수 ↔ nullable | 비고 |
|---|---|---|---|---|---|
| `ProductResponse.id` | `long` / `Long` | `products.id` | `BIGINT` identity PK | 요청에 없음 / NOT NULL | |
| `ProductResponse.name` ← `CreateProductRequest.name` | `String` / `String` | `products.name` | `TEXT` | 필수(@NotBlank) ↔ NOT NULL + CHECK 공백불가 | trim 없이 저장 |
| `ProductResponse.price` ← `CreateProductRequest.price` | `long` / `long` | `products.price` | `BIGINT` | 필수(@NotNull @Positive) ↔ NOT NULL + CHECK > 0 | 원 단위 정수 |
| `ProductResponse.stock` ← `CreateProductRequest.stock` | `int` / `int` | `products.stock` | `INTEGER` | 필수(@NotNull @PositiveOrZero) ↔ NOT NULL + CHECK ≥ 0 | 주문/취소로만 변함 |
| `OrderResponse.id` | `long` / `Long` | `orders.id` | `BIGINT` identity PK | — / NOT NULL | |
| `OrderResponse.status` | `String`(enum 이름) / `OrderStatus` | `orders.status` | `VARCHAR(20)` | 서버가 설정 / NOT NULL + CHECK IN | `@Enumerated(STRING)` |
| `OrderResponse.totalPrice` | `long` / `long` | `orders.total_price` | `BIGINT` | 서버 계산 / NOT NULL + CHECK > 0 | = Σ(unit_price × quantity) |
| `OrderResponse.createdAt` | `Instant` / `Instant` | `orders.created_at` | `TIMESTAMPTZ` | 서버 설정 / NOT NULL | 마이크로초 절삭, ISO-8601 UTC |
| `OrderResponse.items[]` | `List<OrderItemResponse>` / `List<OrderItem>` | `order_items` (FK `order_id`) | — | `items` 필수(@NotEmpty) ↔ 최소 1행 | `id ASC` 순서 |
| `OrderItemResponse.productId` ← `OrderItemRequest.productId` | `long` / `Long` | `order_items.product_id` | `BIGINT` FK→products | 필수(@NotNull) ↔ NOT NULL | 엔티티는 연관 대신 id 값 보관 |
| `OrderItemResponse.quantity` ← `OrderItemRequest.quantity` | `int` / `int` | `order_items.quantity` | `INTEGER` | 필수(@NotNull @Min(1)) ↔ NOT NULL + CHECK ≥ 1 | |
| `OrderItemResponse.unitPrice` | `long` / `long` | `order_items.unit_price` | `BIGINT` | 서버 설정(주문 시점 `products.price`) / NOT NULL + CHECK > 0 | 이후 상품가와 독립 |
| (응답 비노출) | `Long` | `order_items.id` | `BIGINT` identity PK | — | 항목 순서 결정용 내부 키 |
| `OrderPageResponse.totalElements` | `long` | `COUNT(*) FROM orders` | — | — | Spring Data count 쿼리 |

민감 필드: 없음(인증 범위 제외). 응답에서 빠지는 컬럼은 `order_items.id`뿐이다.

### (b) 제약/규칙 ↔ 상태코드 매핑표

| 규칙 / 제약 | 1차 판정 위치 | 상태 | problem type | DB 방어선 (위반 시) |
|---|---|---|---|---|
| JSON 문법 오류·빈 본문·정수 필드에 소수/범위 초과 | Jackson 역직렬화 | 400 | malformed-request | — |
| path `{id}`, query `page`/`size` 타입 불일치 | 인자 변환 | 400 | invalid-parameter | — |
| `name` 필수·공백만 불가 | `@NotBlank` | 400 | validation-error | `ck_products_name_not_blank` (도달 시 500 = 버그) |
| `price > 0` | `@Positive` | 400 | validation-error | `ck_products_price_positive` |
| `stock ≥ 0` (등록 시) | `@PositiveOrZero` | 400 | validation-error | `ck_products_stock_non_negative` |
| `items` 1개 이상, 원소 non-null | `@NotEmpty`, `@NotNull` | 400 | validation-error | — |
| `productId` 필수 | `@NotNull` | 400 | validation-error | `order_items.product_id NOT NULL` |
| `quantity ≥ 1` | `@Min(1)` | 400 | validation-error | `ck_order_items_quantity_positive` |
| 같은 `productId` 중복 불가 | `@AssertTrue isProductIdsUnique()` | 400 | validation-error | `uq_order_items_order_product` |
| `page ≥ 0`, `1 ≤ size ≤ 100` | 메서드 검증 `@Min/@Max` | 400 | validation-error | — |
| 금액 합계 `long` 오버플로 | `Math.*Exact` (서비스) | 400 | validation-error | — (우선순위 예외 사례, §3 R3 주) |
| 상품 존재 (R2, R3) | `findById` / `findAllById` | 404 | product-not-found | `fk_order_items_product` |
| 주문 존재 (R4, R5) | `findWithItemsById` / `existsById` | 404 | order-not-found | — |
| 재고 충분 (R3, R7) | 조건부 UPDATE 영향 행 0 | 409 | insufficient-stock | `ck_products_stock_non_negative` (조건부 UPDATE가 막으므로 도달 불가) |
| 이미 취소된 주문 (R5) | 조건부 UPDATE `WHERE status='ORDERED'` 영향 행 0 | 409 | order-already-cancelled | `ck_orders_status` |
| 그 밖의 예외 | — | 500 | internal-error | — |

DB 제약(CHECK·UNIQUE·FK)은 앱 검증 뒤에 있는 **이중 방어선**이다. 정상 경로에서는 앱 계층이 먼저 판정하므로 DB 제약 위반이 일어나지 않는다. 따라서 `DataIntegrityViolationException`을 4xx로 매핑하지 않는다. 일어나면 버그이므로 500(internal-error)으로 드러나게 둔다.
