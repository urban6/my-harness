# 02 — DB 설계: 주문(Order) + 재고 차감

- DB: PostgreSQL (테스트: Testcontainers `postgres:16-alpine` 권장)
- 마이그레이션: Flyway (`flyway-core` + `flyway-database-postgresql`, 이미 `build.gradle`에 있음)
- 스키마 소유권: **Flyway가 단독 소유**한다. Hibernate는 `ddl-auto: validate`로 검증만 한다.
- API 필드 ↔ 컬럼 대응과 제약 ↔ 상태코드 매핑은 [01_api_design.md §9 정합 요약](./01_api_design.md#9-정합-요약-구현검증-기준표)이 **유일한 기준표**다. 이 문서는 그 표를 복제하지 않는다.

## 1. 테이블 개요

| 테이블 | 역할 | 행 수명 |
|---|---|---|
| `products` | 상품, 현재 재고 | 등록 후 `stock`만 변경(주문 차감, 취소 복원). 수정·삭제 API 없음 |
| `orders` | 주문 헤더(상태, 총액, 생성 시각) | 생성 후 `status`만 `ORDERED` → `CANCELLED`로 단방향 변경 |
| `order_items` | 주문 항목(상품, 수량, 주문 시점 단가) | 생성 후 불변 |

관계: `orders 1 ─ N order_items N ─ 1 products`

## 2. 컬럼 정의

### 2.1 `products`

| 컬럼 | 타입 | NULL | 기본값 | 제약 | JPA 매핑 |
|---|---|---|---|---|---|
| `id` | `bigint` | NOT NULL | identity | PK | `@Id @GeneratedValue(strategy = IDENTITY) Long id` |
| `name` | `varchar(255)` | NOT NULL | — | `ck_products_name_not_blank`: `btrim(name) <> ''` | `@Column(nullable = false, length = 255) String name` |
| `price` | `bigint` | NOT NULL | — | `ck_products_price_positive`: `price > 0` | `@Column(nullable = false) long price` |
| `stock` | `integer` | NOT NULL | — | `ck_products_stock_non_negative`: `stock >= 0` | `@Column(nullable = false) int stock` |

- `stock >= 0` CHECK는 재고 음수를 막는 **최종 방어선**이다. 정상 경로에서는 조건부 UPDATE(`AND stock >= :q`)가 먼저 막는다.
- `btrim`은 공백(스페이스)만 제거하므로 Java `@NotBlank`(모든 공백 문자)보다 약하다. 하지만 `@NotBlank`를 통과한 값은 이 CHECK도 반드시 통과한다(포함 관계).
- 상품 생성 시각 컬럼은 API에 노출하지 않고 요구사항에도 없어서 두지 않는다.

### 2.2 `orders`

| 컬럼 | 타입 | NULL | 기본값 | 제약 | JPA 매핑 |
|---|---|---|---|---|---|
| `id` | `bigint` | NOT NULL | identity | PK | `@Id @GeneratedValue(strategy = IDENTITY) Long id` |
| `status` | `varchar(20)` | NOT NULL | — | `ck_orders_status`: `status IN ('ORDERED','CANCELLED')` | `@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) OrderStatus status` |
| `total_price` | `bigint` | NOT NULL | — | `ck_orders_total_price_positive`: `total_price > 0` | `@Column(name = "total_price", nullable = false, updatable = false) long totalPrice` |
| `created_at` | `timestamptz` | NOT NULL | — (앱이 설정) | — | `@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz") Instant createdAt` |

- 엔티티 클래스 `Order` + `@Table(name = "orders")`. `order`는 SQL 예약어라 테이블명은 복수형을 쓴다.
- `total_price` **저장 결정:** 주문 생성 시 `Σ Math.multiplyExact(unit_price, quantity)`(`Math.addExact`로 합산)를 계산해 저장한다. items는 불변이므로 저장값과 재계산값이 어긋날 수 없다. 목록·조회 응답은 저장값을 그대로 쓰며, 엔티티 getter에서 매번 재계산하지 않는다. 취소해도 바뀌지 않는다.
- `created_at`은 `@PrePersist`에서 `Instant.now().truncatedTo(ChronoUnit.MICROS)`로 설정한다. DB 기본값(`now()`)을 두지 않아 값의 출처를 하나로 유지한다.
- `columnDefinition = "timestamptz"`를 쓰는 이유: Hibernate 6은 `Instant`에 기대 타입 `timestamp(6) with time zone`을 쓰고, PgJDBC는 컬럼 타입명을 `timestamptz`로 보고한다. `ddl-auto: validate`가 타입 코드 비교에서 불일치로 판정할 위험이 있다. `columnDefinition`을 주면 타입명 접두 비교로 확실히 일치한다.
- `OrderStatus`는 enum `ORDERED`, `CANCELLED`. 도메인 메서드 `Order.cancel()`이 상태 전이를 담당한다. 이미 `CANCELLED`면 `OrderAlreadyCancelledException`을 던진다.
- [Q.] JPQL에서 엔티티명 `Order`가 키워드와 충돌하면 `@Entity(name = "PurchaseOrder")`로 바꾼다. Hibernate 6 HQL은 `Order`를 식별자로 허용하므로 기본안은 `Order` 그대로다.

### 2.3 `order_items`

| 컬럼 | 타입 | NULL | 기본값 | 제약 | JPA 매핑 |
|---|---|---|---|---|---|
| `id` | `bigint` | NOT NULL | identity | PK | `@Id @GeneratedValue(strategy = IDENTITY) Long id` |
| `order_id` | `bigint` | NOT NULL | — | FK `fk_order_items_order` → `orders(id)` | `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) Order order` |
| `product_id` | `bigint` | NOT NULL | — | FK `fk_order_items_product` → `products(id)` | `@Column(name = "product_id", nullable = false) Long productId` (**연관 매핑 아님**) |
| `quantity` | `integer` | NOT NULL | — | `ck_order_items_quantity_positive`: `quantity >= 1` | `@Column(nullable = false) int quantity` |
| `unit_price` | `bigint` | NOT NULL | — | `ck_order_items_unit_price_positive`: `unit_price > 0` | `@Column(name = "unit_price", nullable = false) long unitPrice` |

- 테이블 제약 `uq_order_items_order_product` UNIQUE(`order_id`, `product_id`)는 "같은 productId 중복 불가"의 DB 쪽 방어다(API 400 규칙과 짝).
- `unit_price`는 주문 시점 `products.price` **스냅숏**이다. 이후 상품 가격이 바뀌어도(현재는 수정 API 없음) 주문 내역은 바뀌지 않는다.
- `productId`를 `@ManyToOne Product`가 아닌 스칼라로 매핑하는 이유:
  - 응답에 필요한 것은 id뿐이다.
  - 연관 매핑을 하면 주문 조회 시 Product 로딩이나 프록시 문제가 생길 수 있다.
  - 재고는 벌크 UPDATE로만 다루므로 엔티티 그래프가 필요 없다.
  - FK는 DB에 있으므로 참조 무결성은 그대로 유지된다.
- `Order` 쪽 매핑: `@OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST) @OrderBy("id ASC") List<OrderItem> items`. 요청 순서대로 추가·persist하면 id 오름차순이 곧 요청 순서가 된다.

## 3. 인덱스

| 인덱스 | 대상 | 용도 |
|---|---|---|
| `products_pkey` | `products(id)` | 단건 조회, 조건부 UPDATE `WHERE id = :id` |
| `orders_pkey` | `orders(id)` | 단건 조회, 취소 `FOR UPDATE` |
| `idx_orders_created_at_id` | `orders(created_at DESC, id DESC)` | R6 목록 `ORDER BY created_at DESC, id DESC LIMIT/OFFSET`. 정렬 키와 방향이 같아 정렬 없이 인덱스 순서로 스캔한다 |
| `uq_order_items_order_product` | `order_items(order_id, product_id)` | 선두 컬럼 `order_id`가 주문별 items 로딩(`WHERE order_id IN (...)`)을 겸한다. 별도 `order_id` 인덱스는 불필요 |
| (만들지 않음) `order_items(product_id)` | — | 상품 삭제나 상품별 주문 조회 기능이 없어 FK 역방향 조회가 일어나지 않는다. 기능이 추가되면 만든다 |

## 4. Flyway 마이그레이션

- 경로: `src/main/resources/db/migration/V1__create_product_order_tables.sql`
- 기존 `src/main/resources/db/migration/.gitkeep`은 남겨도 무방하다.
- 이후 스키마 변경은 `V2__...sql`로 추가한다. **이미 적용된 V1은 수정하지 않는다**(체크섬 불일치).

```sql
-- V1__create_product_order_tables.sql
-- 주문(Order) + 재고 차감: products, orders, order_items

CREATE TABLE products (
    id     BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    name   VARCHAR(255) NOT NULL,
    price  BIGINT       NOT NULL,
    stock  INTEGER      NOT NULL,
    CONSTRAINT pk_products                    PRIMARY KEY (id),
    CONSTRAINT ck_products_name_not_blank     CHECK (btrim(name) <> ''),
    CONSTRAINT ck_products_price_positive     CHECK (price > 0),
    CONSTRAINT ck_products_stock_non_negative CHECK (stock >= 0)
);

CREATE TABLE orders (
    id          BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    status      VARCHAR(20) NOT NULL,
    total_price BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_orders                      PRIMARY KEY (id),
    CONSTRAINT ck_orders_status               CHECK (status IN ('ORDERED', 'CANCELLED')),
    CONSTRAINT ck_orders_total_price_positive CHECK (total_price > 0)
);

-- R6 목록: ORDER BY created_at DESC, id DESC
CREATE INDEX idx_orders_created_at_id ON orders (created_at DESC, id DESC);

CREATE TABLE order_items (
    id         BIGINT  GENERATED BY DEFAULT AS IDENTITY,
    order_id   BIGINT  NOT NULL,
    product_id BIGINT  NOT NULL,
    quantity   INTEGER NOT NULL,
    unit_price BIGINT  NOT NULL,
    CONSTRAINT pk_order_items                     PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order               FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_order_items_product             FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT uq_order_items_order_product       UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity_positive   CHECK (quantity >= 1),
    CONSTRAINT ck_order_items_unit_price_positive CHECK (unit_price > 0)
);
```

참고: PK 제약에 이름을 붙였으므로(`pk_products` 등) §3 표의 `*_pkey`는 실제로는 `pk_*` 이름으로 생성된다.

## 5. 설정 (application.yml)

`src/main/resources/application.yml` 전문(기존 `spring.application.name` 유지):

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/order}
    username: ${SPRING_DATASOURCE_USERNAME:order}
    password: ${SPRING_DATASOURCE_PASSWORD:}      # 비밀값 기본값 없음(빈 값) — 실행 환경에서 주입
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        default_batch_fetch_size: 100            # R6 목록의 items N+1 방지(IN 배치 로딩)
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    deserialization:
      accept-float-as-int: false                 # 금액·수량에 소수 거절 → 400 (01 §5.4)
    mapper:
      allow-coercion-of-scalars: false           # "1000" 문자열 → 정수 강제 변환 거절 → 400

server:
  port: ${SERVER_PORT:8080}
```

- 표준 속성 `spring.datasource.url`·`username`·`password`를 그대로 쓴다. 환경 변수 `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD`는 relaxed binding으로 이 키들을 덮어쓴다. 플레이스홀더는 이 사실을 파일에서 보이게 하려고 적은 것이다. `SERVER_PORT`도 같다.
- password 기본값을 **빈 문자열**로 둔 이유: 플레이스홀더를 해석하지 못하면 바인딩 단계에서 기동이 실패한다. Testcontainers `@ServiceConnection`이 접속 정보를 대체하는 테스트에서도 마찬가지다. 실제 비밀값은 커밋하지 않는다.
- 트랜잭션 격리 수준은 PostgreSQL 기본값 READ COMMITTED를 그대로 쓴다. 조건부 UPDATE의 재평가 동작이 이 수준을 전제로 한다(01 §7.1).
- 테스트: `@ServiceConnection PostgreSQLContainer<?>("postgres:16-alpine")`로 datasource를 주입하면 Flyway V1 → Hibernate validate 순서로 기동된다. `ddl-auto`를 `create`로 바꾸지 않는다(마이그레이션 검증이 무의미해진다).

## 6. 리포지토리 쿼리 (스키마 의존부)

동시성 근거와 호출 순서는 [01 §7](./01_api_design.md#7-트랜잭션동시성-설계-r3-원자성-r5-r7)에 있다. 여기서는 SQL·JPQL 형태만 확정한다.

| 리포지토리 메서드 | 정의 | 실행 SQL(요지) | 사용처 |
|---|---|---|---|
| `ProductRepository.findAllById(ids)` | JpaRepository 기본 | `SELECT … FROM products WHERE id IN (…)` | R3 존재 확인(404)·가격 스냅숏 |
| `ProductRepository.decreaseStock(Long id, int quantity): int` | `@Modifying @Query("update Product p set p.stock = p.stock - :quantity where p.id = :id and p.stock >= :quantity")` | `UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?` | R3 차감. 반환 0 → 409 |
| `ProductRepository.increaseStock(Long id, int quantity): int` | `@Modifying @Query("update Product p set p.stock = p.stock + :quantity where p.id = :id")` | `UPDATE products SET stock = stock + ? WHERE id = ?` | R5 복원 |
| `OrderRepository.findByIdForUpdate(Long id): Optional<Order>` | `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from Order o where o.id = :id")` | `SELECT … FROM orders WHERE id = ? FOR UPDATE` (Hibernate 방언에 따라 `FOR NO KEY UPDATE`일 수 있음. 두 경우 모두 취소끼리는 직렬화됨) | R5 |
| `OrderRepository.findAll(Pageable)` | JpaRepository 기본, `Sort.by(desc("createdAt"), desc("id"))` | `SELECT … FROM orders ORDER BY created_at DESC, id DESC OFFSET ? LIMIT ?` + `SELECT count(*) FROM orders` | R6 |

- `decreaseStock`·`increaseStock`은 영속성 컨텍스트를 거치지 않는 벌크 연산이다. 같은 트랜잭션에서 로드한 `Product` 엔티티의 `stock`을 수정하지 않는다(오래된 값으로 덮어쓰는 것을 방지).
- 스키마 차원의 데드락 회피: 차감과 복원 모두 `product_id` 오름차순으로 실행한다. `order_items` INSERT는 FK 검사 때문에 `products` 행에 `FOR KEY SHARE`를 잡는다. 이 락은 non-key UPDATE의 `FOR NO KEY UPDATE`와 충돌하지 않으므로 추가 대기를 만들지 않는다.
