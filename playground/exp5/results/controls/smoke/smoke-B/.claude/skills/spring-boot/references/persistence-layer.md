# 영속성 레이어 — 엔티티 · 리포지토리 · 쿼리

## 엔티티

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Order() { }   // JPA 요구: 기본 생성자(외부 사용 막으려 protected)

    public Order(String customerName, int quantity, BigDecimal unitPrice) {
        this.customerName = customerName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public BigDecimal getTotalPrice() {          // 도메인 로직은 엔티티에
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
    // getters...
}
```

주의:
- DB 스키마 설계 문서/마이그레이션이 있으면 그 스키마·제약·인덱스에 정확히 맞춘다.
- `equals`/`hashCode`는 신중히 — 가변 필드로 만들지 말 것. 필요하면 비즈니스 키 또는 할당된 ID 기반으로.
- `@GeneratedValue` 전략은 DB에 맞춘다(PostgreSQL은 보통 `IDENTITY` 또는 시퀀스).
- 연관관계는 기본 `LAZY`(특히 `@ManyToOne`은 명시적으로 `fetch = FetchType.LAZY`).

## 리포지토리

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 파생 쿼리 — 메서드 이름으로 자동 생성
    Page<Order> findByCustomerName(String customerName, Pageable pageable);

    boolean existsByCustomerName(String customerName);

    // 복잡하면 @Query (JPQL)
    @Query("select o from Order o where o.unitPrice >= :min")
    List<Order> findExpensive(@Param("min") BigDecimal min);
}
```

## 페이지네이션

- 목록은 `List`가 아니라 `Page<T>` + `Pageable`로 반환한다.
- 컨트롤러에서 `Pageable`을 받아 그대로 전달(`web-layer.md`).
- 정렬은 `Pageable`의 `Sort`로. 사용자 입력 정렬 필드는 허용 목록으로 검증.

## N+1 회피

- 목록 조회에서 연관 엔티티가 필요하면 **fetch join** 또는 `@EntityGraph`:
  ```java
  @EntityGraph(attributePaths = "items")
  Page<Order> findAll(Pageable pageable);
  ```
- fetch join + 페이지네이션 동시 사용은 메모리 페이징 경고가 날 수 있으니 주의(카운트 분리 쿼리 고려).
- 지연 로딩을 트랜잭션 밖(예: 컨트롤러/직렬화 시점)에서 건드리면 `LazyInitializationException` — 필요한 데이터는 서비스 트랜잭션 안에서 DTO로 변환해 내보낸다.

## 영속성 컨텍스트

- 조회한 엔티티는 트랜잭션 내에서 **더티 체킹**으로 변경이 반영된다 — 굳이 `save()`를 다시 부를 필요가 없을 수 있다.
- `saveAndFlush`/명시적 flush는 필요할 때만.
- 스키마 관리는 프로젝트 관례를 따른다. 운영에서 `ddl-auto: update` 지양 — **Flyway/Liquibase** 마이그레이션 권장(`configuration.md`).

## 체크리스트

- [ ] 엔티티가 (있다면) DB 스키마 설계·제약과 일치하는가?
- [ ] `@ManyToOne`/`@OneToOne`이 `LAZY`인가?
- [ ] 목록이 `Page`+`Pageable`인가?
- [ ] 연관 로딩이 필요한 목록에 N+1 대책(fetch join/`@EntityGraph`)이 있는가?
- [ ] 지연 로딩을 트랜잭션 밖에서 접근하지 않는가?
