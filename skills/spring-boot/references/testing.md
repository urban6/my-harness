# 테스트 — 슬라이스 · 통합 · Testcontainers

> **작성 주체는 `test-writer` 에이전트다.** 이 문서는 backend-impl이 (1) 기존 테스트를 실행해 회귀를 확인하고, (2) 프로젝트의 테스트 관례를 이해하도록 돕는 **참조**다. 새 테스트 작성은 `test-writer`에 위임한다.

## 테스트 계층 선택

| 대상 | 애노테이션 | 로딩 범위 | 언제 |
| --- | --- | --- | --- |
| 컨트롤러(웹 계층) | `@WebMvcTest` | MVC 슬라이스만 | 요청 매핑·검증·상태코드·직렬화 |
| 리포지토리(JPA) | `@DataJpaTest` | JPA 슬라이스 + 내장/실 DB | 쿼리·매핑·제약 |
| 서비스 | 순수 JUnit + Mockito | 컨텍스트 없음 | 비즈니스 로직 단위 |
| 전체 흐름 | `@SpringBootTest` | 전체 컨텍스트 | 엔드투엔드 통합 |

빠르고 좁은 슬라이스를 기본으로, 통합은 꼭 필요한 흐름에만.

## 웹 슬라이스 — @WebMvcTest + MockMvc

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orderService;      // Boot 3.4+ (이전: @MockBean)

    @Test
    void create_returns201_withLocation() throws Exception {
        given(orderService.create(any()))
                .willReturn(new OrderResponse(1L, "kim", 2, new BigDecimal("20.00"), Instant.now()));

        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"customerName":"kim","quantity":2,"unitPrice":10.00}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_returns400_whenInvalid() throws Exception {
        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"customerName":"","quantity":-1}"""))
                .andExpect(status().isBadRequest());
    }
}
```

## JPA 슬라이스 — @DataJpaTest

```java
@DataJpaTest
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;

    @Test
    void findByCustomerName_returnsMatches() {
        orderRepository.save(new Order("kim", 1, new BigDecimal("10.00")));

        Page<Order> result = orderRepository.findByCustomerName("kim", PageRequestOfSize(10));

        assertThat(result).hasSize(1);
    }
}
```

- `@DataJpaTest`는 기본적으로 내장 DB를 쓰고 각 테스트를 롤백한다. **실 DB 특성(방언·제약)을 검증하려면 Testcontainers**를 쓴다.

## Testcontainers — 실제 DB로 통합

```java
@SpringBootTest
@Testcontainers
class OrderIntegrationTest {

    @Container
    @ServiceConnection                     // Boot 3.1+ : 커넥션 자동 연결
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderService orderService;

    @Test
    void createAndFetch_roundTrip() {
        OrderResponse created = orderService.create(
                new CreateOrderRequest("kim", 2, new BigDecimal("10.00")));

        assertThat(orderService.getById(created.id()).customerName()).isEqualTo("kim");
    }
}
```

## 규약

- **given-when-then** 구조, 테스트명은 행동을 서술(`method_expected_condition`).
- 단정은 **AssertJ**(`assertThat`) 권장 — 가독성.
- 외부 의존은 목(`@MockitoBean`/Mockito), DB 특성 검증은 Testcontainers.
- 한 테스트는 하나의 행동을 검증. 과도한 목킹으로 구현에 결합시키지 않는다.
- 프로젝트에 이미 테스트 스타일이 있으면 **그 관례를 따른다**.

## 실행

- Gradle: `./gradlew test` / 특정 클래스 `./gradlew test --tests "*OrderControllerTest"`
- Maven: `mvn test` / `mvn -Dtest=OrderControllerTest test`
