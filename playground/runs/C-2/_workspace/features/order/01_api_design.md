# 01 API 설계 — order (주문 + 재고 차감)

- 스택: Java 21 / Spring Boot 3.5.16 (Spring Framework 6.2) / Spring Data JPA + Hibernate 6 / PostgreSQL / Flyway / JUnit 5 + Testcontainers
- 원문 계약: `feature.md` (필드명·경로·상태코드는 원문 그대로. 이 문서는 원문이 열어 둔 부분만 결정한다)
- DB 설계: [02_db_design.md](./02_db_design.md)
- 정합 요약(구현·검증 기준표): 이 문서 맨 끝 [§9](#9-정합-요약)

---

## 0. 공통 결정

### 0.1 패키지 배치 (기존 루트 `com.example.order` 아래, by-feature)

| 패키지 | 내용 |
|---|---|
| `com.example.order.product` | `ProductController`, `ProductService`, `ProductRepository`, `Product`(엔티티), `ProductNotFoundException`, `dto/CreateProductRequest`, `dto/ProductResponse` |
| `com.example.order.order` | `OrderController`, `OrderService`, `OrderRepository`, `Order`·`OrderItem`(엔티티), `OrderStatus`(enum), `InsufficientStockException`, `OrderNotFoundException`, `OrderAlreadyCancelledException`, `UniqueProductIds`(+Validator), `dto/CreateOrderRequest`, `dto/OrderItemRequest`, `dto/OrderResponse`, `dto/OrderItemResponse` |
| `com.example.order.common` | `PageResponse<T>`, `error/GlobalExceptionHandler`, `error/ProblemTypes` |

도메인 예외는 HTTP를 모른다(`RuntimeException` 상속). 상태코드 매핑은 `GlobalExceptionHandler` 한 곳에서만 한다.

### 0.2 직렬화 규약

| 항목 | 결정 |
|---|---|
| 성공 응답 Content-Type | `application/json` |
| 에러 응답 Content-Type | `application/problem+json` (§7.4 — Accept와 무관하게 강제) |
| 정수 필드 | `id`·`price`·`unitPrice`·`totalPrice` → JSON number(Java `long`), `stock`·`quantity`·`page`·`size` → JSON number(Java `int`), `totalElements` → JSON number(`long`) |
| `createdAt` | UTC, 밀리초 3자리 고정: `"2026-10-09T12:34:56.789Z"`. Java 타입 `Instant`, 생성 시 `truncatedTo(ChronoUnit.MILLIS)`. 응답 record 컴포넌트에 `@JsonFormat(shape = STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")` (패턴 `X`는 UTC에서 `Z` 출력) |
| 알 수 없는 요청 필드 | 무시 (Spring Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false` 유지). 예: 상품 등록 본문의 `id`는 무시 |

`createdAt`을 밀리초로 자르는 이유: PostgreSQL `timestamptz`는 마이크로초 정밀도에서 **반올림**한다. 자르지 않으면 POST 응답(메모리의 나노초 `Instant`)과 이후 GET 응답(DB에서 읽은 값)의 `createdAt`이 달라질 수 있다. 밀리초로 잘라 두면 저장 전후 값이 동일하다.

### 0.3 Jackson 엄격 모드 (application.yml) — 숫자 필드 400 보장

Jackson 기본값은 `1.5 → 1`(소수 절삭), `"10" → 10`(문자열 강제 변환)을 **조용히 허용**한다. 원문의 "정수(원 단위)" 규약과 "위반 시 400"을 지키기 위해 끈다.

```yaml
spring:
  jackson:
    deserialization:
      accept-float-as-int: false      # 1.5, 1.0 → 파싱 실패 → 400
    mapper:
      allow-coercion-of-scalars: false # "10", "", true → 정수 필드 파싱 실패 → 400
```

요청 DTO의 숫자 필드는 **래퍼 타입**(`Long`, `Integer`)으로 선언한다 — `null`/누락을 `@NotNull`로 잡아 400(`validation-error`)으로 만들기 위함(원시 타입이면 0으로 채워져 규칙 위반이 다른 메시지로 바뀐다).

결과적으로 숫자 필드 입력별 처리는 다음과 같다.

| 입력 예 (`price`) | 처리 경로 | 결과 |
|---|---|---|
| 누락, `null` | Bean Validation `@NotNull` | 400 `validation-error` |
| `0`, `-1` | Bean Validation `@Positive` | 400 `validation-error` |
| `1.5`, `1.0`, `1e3` | Jackson (`accept-float-as-int=false`) | 400 `malformed-request` |
| `"100"`, `""`, `true` | Jackson (`allow-coercion-of-scalars=false`) | 400 `malformed-request` |
| `{}`, `[]` | Jackson 타입 불일치 | 400 `malformed-request` |
| 범위 초과 (`stock: 3000000000`, `price: 1e30` 정수표기) | Jackson 오버플로 | 400 `malformed-request` |

### 0.4 Location 헤더

절대 URI. 현재 요청 URI에 `/{id}`를 붙여 만든다.

```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}").buildAndExpand(body.id()).toUri();
return ResponseEntity.created(location).body(body);
```

- 상품: `http://{host}:{port}/api/products/{id}`
- 주문: `http://{host}:{port}/api/orders/{id}`

테스트는 `endsWith("/api/products/" + id)` 형태로 단정한다(호스트·포트는 환경 의존).

---

## 1. R1 — 상품 등록 `POST /api/products`

### 요청
`Content-Type: application/json`

```json
{ "name": "키보드", "price": 35000, "stock": 10 }
```

```java
public record CreateProductRequest(
        @NotBlank(message = "name은 필수이며 공백만으로 이루어질 수 없습니다.")
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        String name,
        @NotNull(message = "price는 필수입니다.")
        @Positive(message = "price는 0보다 커야 합니다.")
        Long price,
        @NotNull(message = "stock은 필수입니다.")
        @PositiveOrZero(message = "stock은 0 이상이어야 합니다.")
        Integer stock) {}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `name` | string | O | `@NotBlank`(공백·탭·개행만으로 된 문자열 불가), 최대 255자. 저장 시 trim 하지 않고 받은 그대로 저장 |
| `price` | integer (long) | O | `> 0` |
| `stock` | integer (int) | O | `>= 0` |

`name` 최대 255자는 원문에 없는 추가 규칙이다. 컬럼이 `varchar(255)`이므로 초과 시 DB 오류(500)가 나는 대신 400으로 거절하기 위해 둔다. 원문 규칙(필수·공백 불가)과 충돌하지 않는다.

### 응답
- `201 Created`, `Location: .../api/products/{id}`, 본문 = R2 응답과 같은 형태

```json
{ "id": 1, "name": "키보드", "price": 35000, "stock": 10 }
```

### 에러
| 상황 | 상태 | type |
|---|---|---|
| 본문 검증 위반(위 표) | 400 | `validation-error` |
| JSON 문법 오류·본문 없음·타입 불일치(§0.3) | 400 | `malformed-request` |

---

## 2. R2 — 상품 조회 `GET /api/products/{id}`

### 응답 `200 OK`
```java
public record ProductResponse(long id, String name, long price, int stock) {}
```
```json
{ "id": 1, "name": "키보드", "price": 35000, "stock": 10 }
```

### 에러
| 상황 | 상태 | type |
|---|---|---|
| `{id}`가 정수(long)로 변환 불가 (`abc`, `1.5`, 범위 초과) | 400 | `validation-error` (field=`id`) |
| 상품 없음 (0·음수 id 포함) | 404 | `product-not-found` |

---

## 3. R3 — 주문 생성 `POST /api/orders`

### 요청
```json
{ "items": [ { "productId": 1, "quantity": 2 }, { "productId": 3, "quantity": 1 } ] }
```

```java
public record CreateOrderRequest(
        @NotEmpty(message = "items는 1개 이상이어야 합니다.")
        @UniqueProductIds(message = "items에 같은 productId가 중복될 수 없습니다.")
        List<@NotNull(message = "items의 원소는 null일 수 없습니다.") @Valid OrderItemRequest> items) {}

public record OrderItemRequest(
        @NotNull(message = "productId는 필수입니다.")
        Long productId,
        @NotNull(message = "quantity는 필수입니다.")
        @Min(value = 1, message = "quantity는 1 이상이어야 합니다.")
        Integer quantity) {}
```

| 필드 | 타입 | 필수 | 규칙 | 위반 시 errors[].field |
|---|---|---|---|---|
| `items` | array | O | 누락·`null`·`[]` 불가 | `items` |
| `items[i]` | object | O | `null` 원소 불가 | `items[i]` |
| `items[i].productId` | integer (long) | O | `null` 불가. 0·음수는 형식 오류가 아니라 **존재하지 않는 상품 → 404** | `items[i].productId` |
| `items[i].quantity` | integer (int) | O | `>= 1` | `items[i].quantity` |
| (교차) | — | — | 같은 `productId` 중복 불가 | `items` |

`@UniqueProductIds`는 `List<OrderItemRequest>`에 붙는 커스텀 제약(`ConstraintValidator<UniqueProductIds, List<OrderItemRequest>>`)이다. 리스트가 `null`이거나, 원소 또는 `productId`가 `null`이면 그 항목은 건너뛰고(다른 제약이 보고함) **null이 아닌 productId끼리만** 중복을 판정한다. 서비스가 아니라 Bean Validation 단계에서 판정하므로 다른 400과 같은 경로(`MethodArgumentNotValidException`)로 나간다.

### 응답
- `201 Created`, `Location: .../api/orders/{id}`, 본문 = R4 응답과 같은 형태, `status = "ORDERED"`

### 에러
| 상황 | 상태 | type |
|---|---|---|
| 본문 검증 위반(위 표) | 400 | `validation-error` |
| JSON 문법 오류·본문 없음·타입 불일치(`quantity: 1.5`, `"2"`, `items: {}` 등) | 400 | `malformed-request` |
| 존재하지 않는 productId가 하나라도 있음 | 404 | `product-not-found` (없는 id 전부를 오름차순으로 detail에 기재) |
| 재고 부족 항목이 하나라도 있음 | 409 | `insufficient-stock` (부족 항목 전부를 productId 오름차순으로 detail에 기재) |

### 처리 절차 (원자성 R3 + 동시성 R7) — `OrderService.create`, `@Transactional`

동시성 전략은 **비관적 행 락(`SELECT ... FOR UPDATE`), productId 오름차순 단일 쿼리로 획득**이다. 근거·데드락 분석은 [02_db_design.md §4](./02_db_design.md#4-동시성-전략)에 있다.

1. `ids` = 요청 items의 productId를 **오름차순** 정렬(중복 없음이 이미 보장됨).
2. `products = productRepository.findAllByIdInForUpdate(ids)`
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select p from Product p where p.id in :ids order by p.id asc")
   List<Product> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
   ```
   - **이 트랜잭션에서 Product를 읽는 첫 쿼리여야 한다.** 같은 트랜잭션에서 먼저 락 없이 Product를 로드하면 Hibernate가 1차 캐시의 낡은 엔티티를 돌려줘, 락을 잡고도 오래된 `stock`으로 판단하게 된다.
3. `missing = ids − products의 id` → 비어 있지 않으면 `ProductNotFoundException(missing)` → **404**. 아직 쓰기 전이다.
4. `insufficient = { item | product.stock < item.quantity }` → 비어 있지 않으면 `InsufficientStockException(insufficient)` → **409**. 아직 쓰기 전이다.
5. 요청 순서대로 각 item에 대해 `product.decreaseStock(quantity)`(더티 체킹으로 UPDATE), `OrderItem(productId, quantity, unitPrice = product.price)` 생성. `unitPrice`는 **락을 잡은 상태에서 읽은 가격의 스냅샷**이다.
6. `createdAt = Instant.now(clock).truncatedTo(MILLIS)`를 **락 획득 이후인 이 시점에** 부여하고 `Order(status=ORDERED)`를 저장한다(items는 cascade). 
7. 트랜잭션 안에서 `OrderResponse`로 변환해 반환한다(OSIV off).

409 시 전체 롤백 보장:
- 3·4단계 판정이 **모든 쓰기보다 먼저** 끝나므로, 404/409 경로에서는 재고 UPDATE·주문 INSERT가 한 건도 실행되지 않는다.
- 이중 안전장치: 도메인 예외는 모두 `RuntimeException`이라 `@Transactional` 기본 규칙으로 트랜잭션 전체가 롤백되고 행 락이 풀린다. 서비스 안에서 이 예외를 catch하지 않는다.
- `Product.decreaseStock`도 방어적으로 `stock < q`이면 `IllegalStateException`을 던진다(정상 흐름에선 도달 불가). DB에도 `CHECK (stock >= 0)`이 있다.

---

## 4. R4 — 주문 조회 `GET /api/orders/{id}`

### 응답 `200 OK`
```java
public record OrderResponse(
        long id,
        OrderStatus status,               // "ORDERED" | "CANCELLED"
        long totalPrice,
        List<OrderItemResponse> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant createdAt) {}

public record OrderItemResponse(long productId, int quantity, long unitPrice) {}
```
```json
{
  "id": 7,
  "status": "ORDERED",
  "totalPrice": 105000,
  "items": [
    { "productId": 1, "quantity": 2, "unitPrice": 35000 },
    { "productId": 3, "quantity": 1, "unitPrice": 35000 }
  ],
  "createdAt": "2026-10-09T12:34:56.789Z"
}
```

| 필드 | 결정 |
|---|---|
| `items` 순서 | **요청에 들어온 순서**(= `order_items.id` 오름차순, 엔티티 `@OrderBy("id ASC")`) |
| `unitPrice` | 주문 시점 `products.price` 스냅샷(`order_items.unit_price`). 이후 상품 가격과 무관 |
| `totalPrice` | **저장하지 않고 계산**: Σ(unitPrice × quantity). `Math.multiplyExact`/`Math.addExact`로 계산해 조용한 오버플로를 막는다(오버플로 시 예외 → 500. 현실적으로 도달 불가). 취소 후에도 값은 그대로 |
| `createdAt` | §0.2 형식 |

### 에러
| 상황 | 상태 | type |
|---|---|---|
| `{id}` 정수 변환 불가 | 400 | `validation-error` (field=`id`) |
| 주문 없음 | 404 | `order-not-found` |

조회는 `@Transactional(readOnly = true)`에서 `findById` 후 items를 지연 로딩하고 트랜잭션 안에서 DTO로 변환한다.

---

## 5. R5 — 주문 취소 `POST /api/orders/{id}/cancel`

- 요청 본문 없음(보내도 무시).
- 응답 `200 OK`, 본문 = R4 형태, `status = "CANCELLED"`. `id`·`items`·`totalPrice`·`createdAt`은 취소 전과 같다.

### 에러
| 상황 | 상태 | type |
|---|---|---|
| `{id}` 정수 변환 불가 | 400 | `validation-error` (field=`id`) |
| 주문 없음 | 404 | `order-not-found` |
| 이미 `CANCELLED` | 409 | `order-already-cancelled` |

### 처리 절차 — `OrderService.cancel`, `@Transactional`
1. `order = orderRepository.findByIdForUpdate(id)` — 주문 행에 `FOR UPDATE`. 없으면 404.
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select o from Order o where o.id = :id")
   Optional<Order> findByIdForUpdate(@Param("id") Long id);
   ```
   - items를 fetch join하지 않는다(PostgreSQL은 outer join의 nullable 쪽에 `FOR UPDATE`를 걸 수 없어 오류). items는 이후 지연 로딩한다.
   - 이 트랜잭션에서 Order를 읽는 첫 쿼리여야 한다(1차 캐시 문제, §3과 같은 이유).
2. `order.status == CANCELLED` → `OrderAlreadyCancelledException` → 409.
3. `ids` = items의 productId 오름차순 → `productRepository.findAllByIdInForUpdate(ids)`(§3과 같은 메서드, 같은 락 순서).
4. 각 item에 대해 `product.increaseStock(quantity)`.
5. `order.cancel()` → `status = CANCELLED`. 커밋.

동시 취소 중복 복원 방지: 같은 주문에 대한 두 번째 취소 트랜잭션은 1단계 주문 행 락에서 대기한다. 첫 트랜잭션이 커밋되면 PostgreSQL READ COMMITTED가 **최신 커밋 버전**을 돌려주므로 두 번째는 `CANCELLED`를 보고 409로 끝난다. 재고는 한 번만 복원된다.

---

## 6. R6 — 주문 목록 `GET /api/orders?page&size`

### 쿼리 파라미터
| 이름 | 타입 | 기본 | 규칙 |
|---|---|---|---|
| `page` | int | `0` | `>= 0` |
| `size` | int | `20` | `1 ~ 100` |

```java
@GetMapping
public PageResponse<OrderResponse> list(
        @RequestParam(name = "page", defaultValue = "0")
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
        @RequestParam(name = "size", defaultValue = "20")
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.") int size)
```

- **`Pageable` 인자를 쓰지 않는다.** Spring Data의 `PageableHandlerMethodArgumentResolver`는 범위 밖·숫자 아닌 값을 조용히 기본값/최대값으로 보정하므로 "범위 밖이면 400" 계약을 깨뜨린다.
- 검증은 Spring MVC 6.1+ **내장 메서드 검증**에 맡긴다 → `HandlerMethodValidationException`. **컨트롤러 클래스에 `@Validated`를 붙이지 않는다**(붙이면 AOP 검증으로 바뀌어 `ConstraintViolationException`이 나오고 핸들러 매핑이 달라진다).
- `page=abc`, `size=1.5`, `page=2147483648` → `MethodArgumentTypeMismatchException` → 400.
- `page=`(빈 문자열) → `defaultValue`가 적용된다(Spring 기본 동작). 400 아님.

### 응답 `200 OK`
```java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {}
```
```json
{ "content": [ { "id": 9, "status": "ORDERED", "totalPrice": 35000, "items": [...], "createdAt": "..." } ],
  "page": 0, "size": 20, "totalElements": 9 }
```
- 필드는 정확히 이 4개다. Spring `Page`를 직접 직렬화하지 않는다.
- `page`·`size`는 요청값(또는 기본값)을 그대로 돌려준다.
- 정렬: `createdAt DESC, id DESC` 고정 (`Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))`). 클라이언트 정렬 파라미터는 받지 않는다.
- 마지막 페이지를 넘는 `page` → `200`, `content: []`, `totalElements`는 실제 전체 개수.
- `(long) page * size > Integer.MAX_VALUE`이면 쿼리 없이 `content: []` + count만 반환한다(JPA `setFirstResult(int)` 오버플로로 500이 나는 것을 방지).
- N+1 회피: 주문 페이지 1쿼리 + count 1쿼리 + items는 `hibernate.default_batch_fetch_size: 100`으로 1쿼리(IN 배치). 컬렉션 fetch join + 페이징(메모리 페이징)을 쓰지 않는다.

### 에러
| 상황 | 상태 | type |
|---|---|---|
| `page < 0`, `size < 1`, `size > 100` | 400 | `validation-error` (field=`page`/`size`) |
| `page`·`size`가 정수로 변환 불가 | 400 | `validation-error` (field=`page`/`size`) |

---

## 7. R8 — 에러 응답 (RFC 9457 Problem Details)

### 7.1 공통 형식
```json
{
  "type": "https://example.com/problems/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "재고가 부족합니다: productId=1 (요청 1, 재고 0)",
  "instance": "/api/orders"
}
```
- 필수 필드: `type`, `title`, `status`, `detail` — **모든 계약 에러에서 4개 모두 채운다**(`type`을 `about:blank`로 두지 않는다).
- `instance`는 Spring이 요청 경로로 채운다(계약 외, 있어도 됨).
- 400 `validation-error`만 확장 필드 `errors`를 가진다.

### 7.2 type URI 체계
`https://example.com/problems/{slug}` — 상수 클래스 `ProblemTypes`에 모은다. 역참조 가능할 필요는 없는 안정 식별자다.

| slug | status | title | detail 형식 | 발생 원인(예외) |
|---|---|---|---|---|
| `validation-error` | 400 | `Validation Failed` | `요청 값이 유효하지 않습니다: {필드1}, {필드2}` (필드명 중복 제거, 사전순) | `MethodArgumentNotValidException`(본문 Bean Validation), `HandlerMethodValidationException`(page/size), `MethodArgumentTypeMismatchException`(path·query 타입 변환 실패) |
| `malformed-request` | 400 | `Malformed Request Body` | 필드 경로를 알 수 있으면 `요청 본문의 '{path}' 값 형식이 올바르지 않습니다.`(예: `items[0].quantity`), 모르면 `요청 본문을 JSON으로 해석할 수 없습니다.` | `HttpMessageNotReadableException`(JSON 문법 오류, 본문 없음, 타입 불일치, 정수 아님, 오버플로) |
| `product-not-found` | 404 | `Product Not Found` | `상품을 찾을 수 없습니다: id={id[, id...]}` (오름차순) | `ProductNotFoundException` |
| `order-not-found` | 404 | `Order Not Found` | `주문을 찾을 수 없습니다: id={id}` | `OrderNotFoundException` |
| `insufficient-stock` | 409 | `Insufficient Stock` | `재고가 부족합니다: productId={pid} (요청 {q}, 재고 {s})[, ...]` (productId 오름차순) | `InsufficientStockException` |
| `order-already-cancelled` | 409 | `Order Already Cancelled` | `이미 취소된 주문입니다: id={id}` | `OrderAlreadyCancelledException` |
| `internal-error` | 500 | `Internal Server Error` | `예상치 못한 오류가 발생했습니다.` (내부 메시지·SQL·스택 노출 금지, 상세는 로그) | 그 외 `Exception` |

`malformed-request`의 path는 `HttpMessageNotReadableException.getCause()`가 `com.fasterxml.jackson.databind.JsonMappingException`이면 `getPath()`의 Reference(fieldName 또는 `[index]`)를 이어 붙여 만든다.

계약 외 프레임워크 오류(없는 경로 404, 405, 415, 406)는 `ResponseEntityExceptionHandler` 기본 ProblemDetail(type `about:blank`)을 그대로 쓰되 Content-Type은 §7.4에 따라 `application/problem+json`이다.

### 7.3 `validation-error`의 `errors` 확장 필드
```json
{
  "type": "https://example.com/problems/validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "요청 값이 유효하지 않습니다: items[0].quantity, items[1].productId",
  "errors": [
    { "field": "items[0].quantity", "message": "quantity는 1 이상이어야 합니다." },
    { "field": "items[1].productId", "message": "productId는 필수입니다." }
  ]
}
```
- `errors`는 `[{field, message}]` 배열이다(같은 필드의 위반이 여럿일 수 있어 Map 대신 배열). field 사전순, 그다음 message 사전순으로 정렬한다.
- `field` 값: 본문은 Bean Validation 경로(`name`, `items`, `items[0]`, `items[0].productId`), 쿼리·경로 파라미터는 파라미터명(`page`, `size`, `id`).
- 타입 변환 실패(path/query)의 message: `{name} 값은 정수여야 합니다.`
- 메시지는 애노테이션 `message`에 명시한 고정 문자열이다(JVM 로케일과 무관). **테스트는 메시지 문구가 아니라 status·type·field로 단정한다.**

### 7.4 핸들러 구조
```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler { ... }
```
- 오버라이드: `handleMethodArgumentNotValid`, `handleHandlerMethodValidationException`, `handleTypeMismatch`(`MethodArgumentTypeMismatchException` 포함), `handleHttpMessageNotReadable` → 위 표대로 type/title/detail/errors 설정.
- `@ExceptionHandler`: 도메인 예외 4종 + `Exception`(500).
- `ResponseEntityExceptionHandler`를 상속하므로 위 4개 프레임워크 예외에 대해 별도의 `@ExceptionHandler`를 **중복 선언하지 않는다**(선언하면 모호한 매핑으로 기동 실패).
- **Content-Type 강제**: `createResponseEntity(...)`를 오버라이드하고, 도메인 예외 핸들러도 같은 헬퍼로 `ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)`를 반환한다. Content-Type을 미리 지정하면 Spring이 Accept 협상을 건너뛰므로, 클라이언트가 `Accept: application/json`만 보내도 `application/problem+json`이 나간다.
- `spring.mvc.problemdetails.enabled`는 켜지 않는다(자체 advice가 같은 역할을 한다).

### 7.5 400 → 404 → 409 우선순위 보장

원문 규약: 한 요청에 오류가 여럿이면 400 → 404 → 409 순으로 먼저 해당하는 것을 반환한다. 이를 **단계 분리**로 보장한다.

| 단계 | 위치 | 판정 | DB 접근 |
|---|---|---|---|
| A. 요청 형식 | 디스패처·컨트롤러 진입 전 (Jackson 역직렬화 → `@Valid` Bean Validation → path·query 타입 변환 → 파라미터 메서드 검증) | **400 전부** | 없음 |
| B. 존재 | 서비스, 트랜잭션 안. 락 조회 직후 | 404 (상품: 요청한 전 productId를 모두 확인한 뒤 판정 / 주문: 주문 행 락 조회) | 락 SELECT |
| C. 상태·재고 | 서비스, B 직후 | 409 (재고 부족 / 이미 취소) | 없음(B 결과 사용) |
| D. 쓰기 | 서비스 | — | UPDATE/INSERT |

- 400 판정 중 DB가 필요한 것은 없다. 중복 productId도 A단계(Bean Validation)에서 판정한다. 따라서 400 사유가 하나라도 있으면 서비스에 진입하지 않는다.
- 주문 생성에서 B단계는 **모든 항목의 존재를 먼저 확인한 뒤** C단계로 넘어간다. 예: `[{없는 상품}, {재고 부족 상품}]` → 404.
- 예: `[{productId: 999(없음), quantity: 0}]` → 400 (A에서 끝남).

---

## 8. 설정 (application.yml) 결정

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: order
    # password: 기본값을 두지 않는다. SPRING_DATASOURCE_PASSWORD 환경 변수로 주입한다.
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        default_batch_fetch_size: 100
  flyway:
    enabled: true            # 기본값. locations 기본 classpath:db/migration
  jackson:
    deserialization:
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false

server:
  port: 8080
```

- 환경 변수 덮어쓰기는 **Spring relaxed binding의 기본 동작**으로 충족된다. OS 환경 변수 프로퍼티 소스는 `application.yml`보다 우선순위가 높고, `SPRING_DATASOURCE_URL` → `spring.datasource.url`, `SPRING_DATASOURCE_USERNAME` → `spring.datasource.username`, `SPRING_DATASOURCE_PASSWORD` → `spring.datasource.password`, `SERVER_PORT` → `server.port`로 매핑된다. 별도 코드나 `${...}` 플레이스홀더가 필요 없다.
- 기본값 표기 방식: url·username·port는 **리터럴 기본값**으로 둔다. `${DB_URL:...}`처럼 별도 이름의 플레이스홀더는 쓰지 않는다(환경 변수 이름이 둘이 되어 혼란스럽다). password는 시크릿이므로 기본값을 두지 않는다(키 자체를 생략해도 환경 변수 바인딩은 동작한다).
- 트랜잭션 격리 수준은 PostgreSQL 기본 **READ COMMITTED를 유지**한다. 락 대기 후 최신 커밋 버전을 읽는 동작(§3, §5)이 이 수준에 의존한다. REPEATABLE READ 이상으로 바꾸면 동시 갱신 시 직렬화 오류가 나서 409가 아니라 500이 된다.
- 시계: `Clock` 빈(`Clock.systemUTC()`)을 등록해 `createdAt` 생성에 주입한다(테스트 고정 가능). 필수는 아니다.
- 테스트: `@TestConfiguration(proxyBeanMethods = false)` 클래스에 `@Bean @ServiceConnection PostgreSQLContainer<?> postgres() { return new PostgreSQLContainer<>("postgres:16-alpine"); }`를 두고 `@Import`로 공유한다. `@ServiceConnection`이 `spring.datasource.*`보다 우선하는 `JdbcConnectionDetails`를 제공하므로 yml 값과 무관하게 컨테이너에 붙는다. Flyway도 같은 DataSource로 마이그레이션한다.
- R7 테스트 참고: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`, 20개 스레드를 `CountDownLatch`로 동시에 출발시킨다. 테스트 메서드에 `@Transactional`을 붙이지 않는다. Hikari 기본 풀 10개로 충분하다(초과 요청은 커넥션을 대기할 뿐 실패하지 않음).

---

## 9. 정합 요약

구현(`backend-impl`)과 검증(`boundary-verifier`, `test-writer`)은 이 절을 기준으로 한다. DB 문서는 이 표를 복제하지 않고 링크로 참조한다.

### 9.1 (a) 응답 필드 ↔ 컬럼 대응표

| 응답 | JSON 필드 | JSON 타입 | Java (DTO) | 엔티티 필드 | 컬럼 | SQL 타입 / 제약 |
|---|---|---|---|---|---|---|
| Product | `id` | number | `long` | `Product.id` (`Long`, IDENTITY) | `products.id` | `bigint` PK identity |
| Product | `name` | string | `String` | `Product.name` | `products.name` | `varchar(255)` NOT NULL, CHECK `btrim(name) <> ''` |
| Product | `price` | number | `long` | `Product.price` (`long`) | `products.price` | `bigint` NOT NULL, CHECK `> 0` |
| Product | `stock` | number | `int` | `Product.stock` (`int`) | `products.stock` | `integer` NOT NULL, CHECK `>= 0` |
| Order | `id` | number | `long` | `Order.id` (`Long`, IDENTITY) | `orders.id` | `bigint` PK identity |
| Order | `status` | string `"ORDERED"`\|`"CANCELLED"` | `OrderStatus` | `Order.status` (`@Enumerated(STRING)`) | `orders.status` | `varchar(20)` NOT NULL, CHECK `IN ('ORDERED','CANCELLED')` |
| Order | `totalPrice` | number | `long` | `Order.getTotalPrice()` (계산, 비영속) | — (저장 안 함) | Σ(`order_items.unit_price` × `order_items.quantity`) |
| Order | `items[].productId` | number | `long` | `OrderItem.productId` (`Long`, 연관 아닌 값 컬럼) | `order_items.product_id` | `bigint` NOT NULL, FK → `products.id` |
| Order | `items[].quantity` | number | `int` | `OrderItem.quantity` (`int`) | `order_items.quantity` | `integer` NOT NULL, CHECK `>= 1` |
| Order | `items[].unitPrice` | number | `long` | `OrderItem.unitPrice` (`long`) | `order_items.unit_price` | `bigint` NOT NULL, CHECK `> 0` (생성 시 `products.price` 스냅샷) |
| Order | `createdAt` | string `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` | `Instant` + `@JsonFormat` | `Order.createdAt` (`Instant`, 밀리초 절삭, `updatable=false`) | `orders.created_at` | `timestamptz` NOT NULL |
| Order | `items` 순서 | — | — | `@OneToMany(mappedBy="order") @OrderBy("id ASC")` | `order_items.id` | 요청 순서 = 삽입 순서 |
| Page | `content` | array | `List<OrderResponse>` | — | `orders` 페이지 | `ORDER BY created_at DESC, id DESC` ↔ 인덱스 `ix_orders_created_at_id` |
| Page | `page`, `size` | number | `int` | — | — | 요청값 그대로 |
| Page | `totalElements` | number | `long` | — | `count(*) FROM orders` | — |

응답에 노출하지 않는 컬럼: `order_items.id`, `order_items.order_id`. 민감 필드는 이 기능에 없다.

요청 필드 필수 여부 ↔ 컬럼 nullable:

| 요청 필드 | 필수 (Bean Validation) | 컬럼 | nullable |
|---|---|---|---|
| `name` | `@NotBlank`, `@Size(max=255)` | `products.name` | NOT NULL |
| `price` | `@NotNull @Positive` | `products.price` | NOT NULL |
| `stock` | `@NotNull @PositiveOrZero` | `products.stock` | NOT NULL |
| `items[].productId` | `@NotNull` | `order_items.product_id` | NOT NULL |
| `items[].quantity` | `@NotNull @Min(1)` | `order_items.quantity` | NOT NULL |
| (서버 결정) `status`, `createdAt`, `unitPrice` | 요청에서 받지 않음 | `orders.status`, `orders.created_at`, `order_items.unit_price` | NOT NULL (서버가 항상 채움) |

원칙: **DB 제약은 애플리케이션 검증보다 같거나 약하다.** 애플리케이션 검증을 통과한 입력은 DB 제약에 걸리지 않는다. DB 제약은 버그 방어선이며, 위반이 DB까지 도달하면 500(`internal-error`)이다(`DataIntegrityViolationException`을 409로 매핑하지 않는다).

### 9.2 (b) 제약·검증 위반 ↔ HTTP 상태코드 매핑표

| # | 엔드포인트 | 위반 | 판정 단계 | 대응 DB 제약 (방어선) | HTTP | type slug | errors[].field |
|---|---|---|---|---|---|---|---|
| 1 | 전체 POST | JSON 문법 오류, 본문 없음 | A (Jackson) | — | 400 | `malformed-request` | — |
| 2 | R1 | `price`·`stock`이 정수 아님(`1.5`, `"10"`, `true`, 객체) 또는 범위 초과 | A (Jackson) | `bigint`/`integer` | 400 | `malformed-request` | — |
| 3 | R1 | `name` 누락/null/""/공백만 | A (BV `@NotBlank`) | `ck_products_name_not_blank`, NOT NULL | 400 | `validation-error` | `name` |
| 4 | R1 | `name` 255자 초과 | A (BV `@Size`) | `varchar(255)` | 400 | `validation-error` | `name` |
| 5 | R1 | `price` 누락/null | A (BV `@NotNull`) | NOT NULL | 400 | `validation-error` | `price` |
| 6 | R1 | `price <= 0` | A (BV `@Positive`) | `ck_products_price_positive` | 400 | `validation-error` | `price` |
| 7 | R1 | `stock` 누락/null | A (BV `@NotNull`) | NOT NULL | 400 | `validation-error` | `stock` |
| 8 | R1 | `stock < 0` | A (BV `@PositiveOrZero`) | `ck_products_stock_non_negative` | 400 | `validation-error` | `stock` |
| 9 | R3 | `items` 누락/null/`[]` | A (BV `@NotEmpty`) | — (앱 규칙) | 400 | `validation-error` | `items` |
| 10 | R3 | `items[i]`가 null | A (BV 원소 `@NotNull`) | — | 400 | `validation-error` | `items[i]` |
| 11 | R3 | `items[i].productId` null | A (BV `@NotNull`) | `order_items.product_id` NOT NULL | 400 | `validation-error` | `items[i].productId` |
| 12 | R3 | `items[i].quantity` null | A (BV `@NotNull`) | NOT NULL | 400 | `validation-error` | `items[i].quantity` |
| 13 | R3 | `items[i].quantity < 1` | A (BV `@Min(1)`) | `ck_order_items_quantity_positive` | 400 | `validation-error` | `items[i].quantity` |
| 14 | R3 | `quantity`·`productId`가 정수 아님 / `items`가 배열 아님 | A (Jackson) | `integer`/`bigint` | 400 | `malformed-request` | — |
| 15 | R3 | 같은 `productId` 중복 | A (BV `@UniqueProductIds`) | `uq_order_items_order_product` | 400 | `validation-error` | `items` |
| 16 | R6 | `page < 0` | A (메서드 검증 `@Min(0)`) | — | 400 | `validation-error` | `page` |
| 17 | R6 | `size < 1` 또는 `size > 100` | A (메서드 검증 `@Min(1)`/`@Max(100)`) | — | 400 | `validation-error` | `size` |
| 18 | R6 | `page`·`size` 정수 변환 불가 | A (타입 변환) | — | 400 | `validation-error` | `page`/`size` |
| 19 | R2·R4·R5 | path `{id}` 정수 변환 불가 | A (타입 변환) | — | 400 | `validation-error` | `id` |
| 20 | R2 | 상품 없음 | B | — | 404 | `product-not-found` | — |
| 21 | R3 | 없는 productId 포함 (0·음수 포함) | B (전 항목 확인 후) | `fk_order_items_product` | 404 | `product-not-found` | — |
| 22 | R4·R5 | 주문 없음 | B | — | 404 | `order-not-found` | — |
| 23 | R3 | 재고 부족 (`stock < quantity`) 항목 존재, 동시 경합으로 소진된 경우 포함(R7) | C (락 보유 상태) | `ck_products_stock_non_negative` | 409 | `insufficient-stock` | — |
| 24 | R5 | 이미 `CANCELLED` (동시 취소의 두 번째 포함) | C (주문 행 락 보유 상태) | `ck_orders_status` | 409 | `order-already-cancelled` | — |
| 25 | 전체 | 예상치 못한 예외(DB 제약 위반 도달 포함) | — | — | 500 | `internal-error` | — |

모든 행의 응답: `Content-Type: application/problem+json`, 본문에 `type`·`title`·`status`·`detail` 존재(§7.2의 title/detail 형식).
