# 01 API 설계 — product (상품 등록·조회)

- 대상 요구사항: R1(상품 등록), R2(상품 조회), R8(에러 포맷). 근거: `00_requirements.json`, `feature.md`
- 스택: Java 21 / Spring Boot 3.5.x (build.gradle 기준 3.5.16) / JPA + Hibernate / PostgreSQL / Flyway / JUnit 5 + Testcontainers
- DB 설계: [`02_db_design.md`](./02_db_design.md)
- 범위 외(설계하지 않음): 주문, 인증·인가, 상품 수정·삭제, 목록 조회

## 0. 기존 코드 관례 (따를 것)

이 프로젝트에는 스타터 코드가 있다. 다음 관례를 그대로 따른다.

| 항목 | 현행 | 이 기능의 결정 |
|---|---|---|
| 진입점 | `com.example.order.OrderApplication` | 변경 없음 |
| 베이스 패키지 | `com.example.order` | 기능 패키지 `com.example.order.product`, 공통 에러 `com.example.order.common.error` (by-feature 구조, 컴포넌트 스캔 범위 안) |
| 빌드 | Gradle(Groovy) `build.gradle`, Boot 3.5.16, flyway-core + flyway-database-postgresql, testcontainers 포함 | 의존성 추가 불필요 |
| 설정 | `application.yml` (`spring.application.name: order-service`) | 기존 키 유지 + 추가 (02 문서 §5) |
| 에러 포맷 | 없음 | RFC 9457 ProblemDetail 신규 도입 (§4) |

권장 파일 배치:

```
com.example.order
├── product/
│   ├── Product.java                 # @Entity
│   ├── ProductRepository.java       # JpaRepository<Product, Long>
│   ├── ProductService.java          # @Transactional
│   ├── ProductController.java       # @RestController @RequestMapping("/api/products")
│   ├── ProductNotFoundException.java
│   └── dto/
│       ├── CreateProductRequest.java  # record
│       └── ProductResponse.java       # record
└── common/error/
    ├── GlobalExceptionHandler.java    # @RestControllerAdvice extends ResponseEntityExceptionHandler
    └── ProblemTypes.java              # type URI 상수
```

## 1. 공통 사항

- Base path: `/api/products`
- 요청 본문: `Content-Type: application/json`
- 성공 응답: `Content-Type: application/json`
- 에러 응답: `Content-Type: application/problem+json` (RFC 9457) — §4
- 인증: 없음(범위 외). 모든 엔드포인트가 공개.
- JSON 필드명: camelCase(`id`, `name`, `price`, `stock`). 이 기능의 필드는 모두 한 단어라 컬럼명과 동일.
- 금액 `price`: 원 단위 정수. 소수·문자열 표기 불허(§3.3).

## 2. 엔드포인트

### 2.1 POST /api/products — 상품 등록 (R1)

**요청 본문** — `CreateProductRequest` (record)

| 필드 | JSON 타입 | Java 타입 | 필수 | 검증 (Bean Validation) | 위반 시 |
|---|---|---|---|---|---|
| `name` | string | `String` | 예 | `@NotBlank` + `@Size(max = 255)` | 400 validation-failed |
| `price` | integer | `Long` | 예 | `@NotNull` + `@Positive` (> 0) | 400 validation-failed |
| `stock` | integer | `Integer` | 예 | `@NotNull` + `@PositiveOrZero` (>= 0) | 400 validation-failed |

- 요청 DTO는 **래퍼 타입**(`Long`, `Integer`)을 쓴다. 원시 타입이면 `null`/누락이 0으로 바뀌어 `@NotNull`이 무력화되고, `stock` 누락이 0으로 저장되는 버그가 생긴다.
- `price`를 `Long`으로 정한 이유: 원 단위 금액은 21억(`Integer.MAX_VALUE` = 2,147,483,647)을 넘을 수 있다(고가 장비 등). 정수 원 단위라 `BigDecimal`은 불필요. 컬럼은 `BIGINT`.
- `stock`은 `Integer`(컬럼 `INTEGER`). 재고 수량이 21억을 넘을 일은 현실적으로 없다.
- `name`은 저장 시 **trim하지 않는다** — 받은 그대로 저장·응답한다. `@NotBlank`는 "공백 문자만으로 구성된 문자열"(빈 문자열 포함)을 거부한다(Java `Character.isWhitespace` 기준).
- `@Size(max = 255)`는 요구사항에 명시되지 않았지만 컬럼 `VARCHAR(255)`에서 **도출한 제약**이다. 이 검증이 없으면 256자 이상 입력이 DB 오류(500)로 새어 나간다. `@Size`는 UTF-16 code unit 수로 세고 PostgreSQL `VARCHAR(n)`은 문자(code point) 수로 세므로, 앱 검증이 DB보다 항상 같거나 엄격하다(이모지 등 보충 문자에서 앱 쪽이 더 엄격) — DB 길이 오류는 발생하지 않는다.
- 알 수 없는 필드는 무시한다(Spring Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`). 요청 본문에 `id`가 있어도 **무시**되며 서버가 id를 생성한다.
- 같은 `name` 중복 등록은 허용한다(요구사항에 유일성 규칙 없음 → unique 제약 없음, 409 없음).

검증 메시지(애노테이션 `message` 속성으로 고정):

| 필드 | 제약 | message |
|---|---|---|
| name | `@NotBlank` | `name은 필수이며 공백만으로 구성될 수 없습니다.` |
| name | `@Size(max = 255)` | `name은 255자 이하여야 합니다.` |
| price | `@NotNull` | `price는 필수입니다.` |
| price | `@Positive` | `price는 0보다 커야 합니다.` |
| stock | `@NotNull` | `stock은 필수입니다.` |
| stock | `@PositiveOrZero` | `stock은 0 이상이어야 합니다.` |

**요청 예**

```http
POST /api/products HTTP/1.1
Content-Type: application/json

{"name": "무선 키보드", "price": 39000, "stock": 10}
```

**성공 응답 — 201 Created**

- 헤더 `Location: /api/products/{id}` — **상대 경로**(서버가 생성한 id). 예: `Location: /api/products/1`
  - 구현: `ResponseEntity.created(URI.create("/api/products/" + created.id()))`. `ServletUriComponentsBuilder.fromCurrentRequest()`는 호스트·`X-Forwarded-*`에 따라 절대 URI가 달라지므로 쓰지 않는다. 테스트는 `Location` 값이 정확히 `/api/products/{응답 본문의 id}`와 일치하는지 검증한다.
- 본문: `ProductResponse` (§2.3) — GET 응답과 동일한 형태.

```http
HTTP/1.1 201 Created
Location: /api/products/1
Content-Type: application/json

{"id": 1, "name": "무선 키보드", "price": 39000, "stock": 10}
```

**에러 응답**

| 상황 | 상태 | type (§4) |
|---|---|---|
| Bean Validation 위반(누락·null·공백·범위) | 400 | `validation-failed` |
| JSON 파싱/타입 불일치(본문 없음 포함) | 400 | `malformed-request-body` |
| 그 외 서버 오류 | 500 | `about:blank` |

### 2.2 GET /api/products/{id} — 상품 조회 (R2)

**경로 변수**

| 이름 | 타입 | 설명 |
|---|---|---|
| `id` | `Long` (`@PathVariable Long id`) | 상품 id |

- `id`가 `Long`으로 변환 불가(`abc`, `1.5`, `99999999999999999999` 등 범위 초과) → **400** `invalid-path-parameter` (§4.3). 404가 아니다 — "그런 리소스가 없다"가 아니라 "요청 형식이 틀렸다"이기 때문.
- `id`가 숫자이지만 존재하지 않음(`0`, 음수 포함) → **404** `product-not-found`. 0·음수를 별도 400으로 거르지 않는다(IDENTITY는 1부터 생성되므로 단순히 "없음").

**성공 응답 — 200 OK**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id": 1, "name": "무선 키보드", "price": 39000, "stock": 10}
```

**에러 응답**

| 상황 | 상태 | type (§4) |
|---|---|---|
| id 형식 오류 | 400 | `invalid-path-parameter` |
| 상품 없음 | 404 | `product-not-found` |
| 그 외 서버 오류 | 500 | `about:blank` |

### 2.3 응답 스키마 — `ProductResponse` (record)

| 필드 | JSON 타입 | Java 타입 | nullable | 설명 |
|---|---|---|---|---|
| `id` | integer | `Long` | 아니오 | 서버 생성 PK |
| `name` | string | `String` | 아니오 | 등록 시 값 그대로(trim 안 함) |
| `price` | integer | `long` | 아니오 | 원 단위, > 0 |
| `stock` | integer | `int` | 아니오 | >= 0 |

- 필드는 정확히 이 4개. 추가 필드 없음. 엔티티를 직접 직렬화하지 않고 `ProductResponse.from(Product)` 같은 정적 팩토리로 변환한다(서비스 트랜잭션 안에서).
- JSON 키 순서 `id, name, price, stock` (record 컴포넌트 순서). 계약상 순서는 의미 없음.

## 3. 검증·파싱 규칙 상세

### 3.1 400 분류 원칙

| 원인 | Spring 예외 | type |
|---|---|---|
| JSON은 유효하고 타입도 맞지만 값이 규칙 위반, 또는 필드 누락/`null` | `MethodArgumentNotValidException` | `validation-failed` |
| JSON 문법 오류, 본문 없음, 타입 변환 불가 | `HttpMessageNotReadableException` | `malformed-request-body` |
| 경로 변수 변환 불가 | `MethodArgumentTypeMismatchException` (`TypeMismatchException`) | `invalid-path-parameter` |

### 3.2 케이스 표 (테스트 기준)

| 요청 본문 | 상태 | type | 비고 |
|---|---|---|---|
| `{"name":"A","price":1,"stock":0}` | 201 | — | 경계값 정상 (price 최소 1, stock 최소 0) |
| `{"price":1000,"stock":1}` (name 누락) | 400 | validation-failed | errors[name] |
| `{"name":null,...}` | 400 | validation-failed | errors[name] |
| `{"name":"",...}` | 400 | validation-failed | errors[name] |
| `{"name":"   ",...}` | 400 | validation-failed | errors[name] (공백만) |
| `{"name":"\t\n",...}` | 400 | validation-failed | errors[name] (공백 문자만) |
| name 256자 | 400 | validation-failed | errors[name] (`@Size`) |
| name 255자 | 201 | — | 경계값 정상 |
| `{"name":"A","stock":1}` (price 누락) | 400 | validation-failed | errors[price] |
| `{"name":"A","price":null,"stock":1}` | 400 | validation-failed | errors[price] |
| `{"name":"A","price":0,"stock":1}` | 400 | validation-failed | errors[price] |
| `{"name":"A","price":-1,"stock":1}` | 400 | validation-failed | errors[price] |
| `{"name":"A","price":1000}` (stock 누락) | 400 | validation-failed | errors[stock] |
| `{"name":"A","price":1000,"stock":null}` | 400 | validation-failed | errors[stock] |
| `{"name":"A","price":1000,"stock":-1}` | 400 | validation-failed | errors[stock] |
| `{}` | 400 | validation-failed | errors에 name·price·stock 3개 모두 |
| `{"name":"A","price":"abc","stock":1}` | 400 | malformed-request-body | 타입 불일치 |
| `{"name":"A","price":"1000","stock":1}` | 400 | malformed-request-body | 숫자 문자열도 불허 (§3.3) |
| `{"name":"A","price":1000.5,"stock":1}` | 400 | malformed-request-body | 소수 불허 (§3.3) |
| `{"name":"A","price":1000,"stock":"x"}` | 400 | malformed-request-body | 타입 불일치 |
| `{"name":"A","price":true,"stock":1}` | 400 | malformed-request-body | 불리언 불허 (§3.3) |
| `{"name":"A","price":9223372036854775808,"stock":1}` | 400 | malformed-request-body | Long 범위 초과 |
| `{"name":"A","price":1000,"stock":2147483648}` | 400 | malformed-request-body | Integer 범위 초과 |
| `{"name":"A","price":1000,` (깨진 JSON) | 400 | malformed-request-body | 문법 오류 |
| 본문 없음 (Content-Type: application/json) | 400 | malformed-request-body | |
| `[]` | 400 | malformed-request-body | 최상위가 객체가 아님 |

### 3.3 Jackson 엄격 모드 (필수 설정)

"price는 정수"를 지키려면 Spring Boot 기본 Jackson 설정을 바꿔야 한다. 기본값으로는 다음이 **조용히 통과**한다.

| 입력 | Jackson 기본 동작 | 문제 |
|---|---|---|
| `"price": 1000.5` | `ACCEPT_FLOAT_AS_INT=true` → 1000으로 절삭 | 금액 손실, 400이어야 함 |
| `"price": "1000"` | `ALLOW_COERCION_OF_SCALARS=true` → 1000으로 변환 | 계약상 정수 타입 위반 |

`application.yml`에 다음을 둔다(02 문서 §5.2에 전체 yml):

```yaml
spring:
  jackson:
    deserialization:
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false
```

- 이 설정 후 위 입력들은 `HttpMessageNotReadableException` → 400 `malformed-request-body`가 된다.
- `name`에 숫자·불리언을 넣는 경우(`"name": 123`)의 처리는 Jackson의 문자열 변환 규칙에 따르며 **계약 테스트 대상이 아니다**(문자열 `"123"`으로 받아들여질 수 있음).

## 4. 에러 계약 (R8) — RFC 9457 Problem Details

### 4.1 공통

- `Content-Type: application/problem+json` — 요청의 `Accept`가 없음/`*/*`/`application/json`/`application/problem+json` 중 무엇이든 동일해야 한다. 구현자는 `ResponseEntityExceptionHandler.createResponseEntity`를 오버라이드하거나 `@ExceptionHandler`에서 `ResponseEntity.status(..).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd)`로 **명시 지정**해 `Accept` 협상에 의존하지 않게 한다. 테스트는 `Accept: application/json`을 붙인 요청으로도 `application/problem+json`을 확인한다.
- 필수 필드: `type`, `title`, `status`, `detail` — 모든 에러 응답에 **항상** 존재(null 불가).
- `instance`: 선택. Spring이 요청 경로(예: `/api/products/42`)로 채울 수 있다. 테스트는 이 필드에 의존하지 않는다.
- 확장 필드: `errors` (validation-failed에만).
- 내부 정보 비노출: Jackson/Hibernate 원문 메시지, 예외 클래스명, Java 타입명(`java.lang.Long`), SQL, 스택트레이스를 `detail`에 넣지 않는다.
- 구현: `com.example.order.common.error.GlobalExceptionHandler` — `@RestControllerAdvice` + `extends ResponseEntityExceptionHandler`. 아래 메서드를 오버라이드/정의한다.
  - `handleMethodArgumentNotValid` → §4.2(a) validation-failed
  - `handleHttpMessageNotReadable` → §4.2(b) malformed-request-body
  - `handleTypeMismatch` → §4.3 invalid-path-parameter
  - `@ExceptionHandler(ProductNotFoundException.class)` → §4.4
  - `@ExceptionHandler(Exception.class)` → §4.5
  - 그 외 Spring MVC 표준 예외(405 Method Not Allowed, 415 Unsupported Media Type, 존재하지 않는 경로 404 등)는 `ResponseEntityExceptionHandler` 기본 동작으로 ProblemDetail(`type: about:blank`)이 나간다. 계약 테스트 대상은 아니지만 Content-Type은 동일하게 `application/problem+json`.
- type URI 상수는 `ProblemTypes`에 모은다. 아래 URI는 안정적 식별자(역참조 가능할 필요 없음).

| 상수 | type URI | title | status |
|---|---|---|---|
| `VALIDATION_FAILED` | `https://example.com/problems/validation-failed` | `Validation Failed` | 400 |
| `MALFORMED_REQUEST_BODY` | `https://example.com/problems/malformed-request-body` | `Malformed Request Body` | 400 |
| `INVALID_PATH_PARAMETER` | `https://example.com/problems/invalid-path-parameter` | `Invalid Path Parameter` | 400 |
| `PRODUCT_NOT_FOUND` | `https://example.com/problems/product-not-found` | `Product Not Found` | 404 |
| (없음) | `about:blank` | `Internal Server Error` | 500 |

`title`은 type별 고정 문자열(RFC 9457: 같은 type이면 title 불변). 상황별 차이는 `detail`에만 담는다.

### 4.2 400 — 요청 본문 오류

**(a) validation-failed** — `MethodArgumentNotValidException`

- `detail`: 고정 `요청 본문 검증에 실패했습니다.`
- `errors`: 배열. 각 원소 `{ "field": string, "message": string }`. 위반 하나당 원소 하나. 정렬: `field` 오름차순, 같은 field면 `message` 오름차순(Hibernate Validator 순서가 비결정적이므로 고정). `message`는 §2.1 메시지 표 그대로.
- 테스트는 `errors[*].field` 집합을 검증하고, 메시지 문자열 일치는 선택.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://example.com/problems/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "요청 본문 검증에 실패했습니다.",
  "instance": "/api/products",
  "errors": [
    { "field": "name",  "message": "name은 필수이며 공백만으로 구성될 수 없습니다." },
    { "field": "price", "message": "price는 0보다 커야 합니다." }
  ]
}
```

**(b) malformed-request-body** — `HttpMessageNotReadableException`

- `detail` 결정 규칙:
  - cause 체인에 Jackson `JsonMappingException`(하위: `MismatchedInputException`, `InvalidFormatException` 등)이 있고 `getPath()`의 마지막 요소에 필드명이 있으면 → `필드 '{field}'의 값 형식이 올바르지 않습니다.`
    - 정수 범위 초과처럼 필드명을 얻을 수 없는 경우는 아래 일반 메시지로 떨어져도 계약 위반 아님.
  - 그 외(문법 오류, 본문 없음, 최상위 타입 불일치) → `요청 본문을 읽을 수 없습니다. 올바른 JSON 형식인지 확인하세요.`
- `errors` 필드 없음.
- 테스트는 status·type·title·Content-Type을 검증하고, `price`에 `"abc"`를 넣은 케이스에서 `detail`에 `price`가 포함되는지 확인한다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://example.com/problems/malformed-request-body",
  "title": "Malformed Request Body",
  "status": 400,
  "detail": "필드 'price'의 값 형식이 올바르지 않습니다.",
  "instance": "/api/products"
}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://example.com/problems/malformed-request-body",
  "title": "Malformed Request Body",
  "status": 400,
  "detail": "요청 본문을 읽을 수 없습니다. 올바른 JSON 형식인지 확인하세요.",
  "instance": "/api/products"
}
```

### 4.3 400 — invalid-path-parameter

- 트리거: `GET /api/products/abc` 등 `id`를 `Long`으로 변환 불가 (`MethodArgumentTypeMismatchException`).
- `detail`: `경로 변수 '{name}'는 정수여야 합니다.` — `{name}`은 `ex.getPropertyName()`(= `id`). 입력값 자체는 에코하지 않는다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://example.com/problems/invalid-path-parameter",
  "title": "Invalid Path Parameter",
  "status": 400,
  "detail": "경로 변수 'id'는 정수여야 합니다.",
  "instance": "/api/products/abc"
}
```

### 4.4 404 — product-not-found

- 트리거: 서비스가 `productRepository.findById(id)`로 못 찾으면 도메인 예외 `ProductNotFoundException(Long id)`를 던진다(HTTP를 모르는 순수 예외). 핸들러가 404로 매핑.
- `detail`: `상품을 찾을 수 없습니다: id={id}` — 예외 메시지 그대로.

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{
  "type": "https://example.com/problems/product-not-found",
  "title": "Product Not Found",
  "status": 404,
  "detail": "상품을 찾을 수 없습니다: id=42",
  "instance": "/api/products/42"
}
```

### 4.5 500 — 예상치 못한 오류

- `type`: `about:blank`, `title`: `Internal Server Error`, `detail`: 고정 `예상치 못한 오류가 발생했습니다.`
- 예외 상세는 서버 로그에만(ERROR 레벨). 응답에 메시지·스택트레이스 비노출.
- DB 제약 위반(`DataIntegrityViolationException`)은 **별도 매핑하지 않는다** — Bean Validation이 DB 제약보다 같거나 엄격하므로(§5b) 정상 경로에서는 발생할 수 없고, 발생하면 앱 버그이므로 500으로 드러나게 한다.

## 5. 정합 요약 (구현·검증 기준표)

> 이 표가 단일 기준이다. `02_db_design.md`는 이 표를 복제하지 않고 여기를 참조한다.

### 5a. 응답 필드 ↔ 컬럼 대응표

| 응답 필드 | JSON 타입 | DTO 타입 (`ProductResponse`) | 요청 필드 / DTO 타입 (`CreateProductRequest`) | 엔티티 필드 (`Product`) | 컬럼 (`products`) | 컬럼 타입 | NULL | 비고 |
|---|---|---|---|---|---|---|---|---|
| `id` | integer | `Long` | (없음 — 서버 생성, 요청의 `id`는 무시) | `Long id` (`@Id @GeneratedValue(IDENTITY)`) | `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | NOT NULL (PK) | Location 헤더에 사용 |
| `name` | string | `String` | `name` / `String` (필수) | `String name` (`length = 255, nullable = false`) | `name` | `VARCHAR(255)` | NOT NULL | trim 없이 저장 |
| `price` | integer | `long` | `price` / `Long` (필수) | `long price` | `price` | `BIGINT` | NOT NULL | 원 단위 |
| `stock` | integer | `int` | `stock` / `Integer` (필수) | `int stock` | `stock` | `INTEGER` | NOT NULL | |

- 노출하지 않는 컬럼: 없음(테이블의 모든 컬럼이 응답에 노출). 민감 필드 없음.
- 요청 필수 여부 ↔ 컬럼 nullable: 요청 3필드 모두 필수 ↔ 컬럼 3개 모두 NOT NULL. 어긋남 없음.
- 타입 폭: `Long`↔`BIGINT`, `Integer`/`int`↔`INTEGER` 일치. Jackson이 범위 초과를 파싱 단계에서 거부하므로 DB 오버플로 불가.

### 5b. 제약 ↔ 상태코드 매핑표

| 규칙 | Bean Validation (요청 DTO) | DB 제약 (`products`, 02 문서 §3) | 같은 규칙인가 | 위반 시 응답 |
|---|---|---|---|---|
| name 필수 | `@NotBlank` (null 거부) | `name NOT NULL` | 동일 | 400 validation-failed |
| name 공백만 불가 | `@NotBlank` (모든 `Character.isWhitespace` 문자) | `ck_products_name_not_blank`: `CHECK (btrim(name, E' \t\n\x0B\f\r') <> '')` | **앱이 더 엄격**: DB는 ASCII 공백 6종(SP·TAB·LF·VT·FF·CR)만 제거해 판단. 6종 모두 Java whitespace이므로 `@NotBlank` 통과 값은 반드시 이 CHECK도 통과 | 400 validation-failed (DB 위반은 도달 불가) |
| name 길이 ≤ 255 | `@Size(max = 255)` (UTF-16 code unit) | `VARCHAR(255)` (code point) | **앱이 같거나 더 엄격** | 400 validation-failed |
| price 필수 | `@NotNull` | `price NOT NULL` | 동일 | 400 validation-failed |
| price > 0 | `@Positive` | `ck_products_price_positive`: `CHECK (price > 0)` | 동일 | 400 validation-failed |
| price 정수 | `Long` 타입 + Jackson 엄격 모드(§3.3) | `BIGINT` | 동일 | 400 malformed-request-body |
| stock 필수 | `@NotNull` | `stock NOT NULL` | 동일 | 400 validation-failed |
| stock ≥ 0 | `@PositiveOrZero` | `ck_products_stock_non_negative`: `CHECK (stock >= 0)` | 동일 | 400 validation-failed |
| stock 정수 | `Integer` 타입 + Jackson 엄격 모드 | `INTEGER` | 동일 | 400 malformed-request-body |
| id 유일 | — (서버 생성) | `pk_products` PRIMARY KEY (IDENTITY) | — | 위반 불가 |
| name 유일 | 없음 | 없음 (unique 제약 없음) | 동일(둘 다 없음) | 중복 허용 → 201 |
| 조회 대상 존재 | — | PK 조회 결과 없음 | — | 404 product-not-found |
| 경로 id 형식 | `@PathVariable Long` 변환 | — | — | 400 invalid-path-parameter |

불변식: **Bean Validation을 통과한 요청은 어떤 DB 제약도 위반하지 않는다.** DB CHECK는 앱을 우회한 쓰기(수동 SQL, 향후 다른 경로)를 막는 방어선이다. 이 불변식이 깨지면(= DB 제약 위반이 500으로 나오면) 설계 또는 구현 결함이다.
