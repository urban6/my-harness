package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class OrderCreateApiTest extends IntegrationTest {

    @Test
    @DisplayName("R3 주문 생성: 201 + Location, R4 형태 본문(status=ORDERED), 재고 차감")
    void create_returns201AndDecreasesStock() throws Exception {
        long keyboard = createProduct("키보드", 30000, 5);
        long mouse = createProduct("마우스", 15000, 10);

        String location = postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":3}]}
                """.formatted(keyboard, mouse))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/orders/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.totalPrice").value(30000 * 2 + 15000 * 3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(keyboard))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(30000))
                .andExpect(jsonPath("$.items[1].productId").value(mouse))
                .andExpect(jsonPath("$.items[1].quantity").value(3))
                .andExpect(jsonPath("$.items[1].unitPrice").value(15000))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        assertThat(stockOf(keyboard)).isEqualTo(3);
        assertThat(stockOf(mouse)).isEqualTo(7);
    }

    @Test
    @DisplayName("R3 주문 생성: 재고와 같은 수량은 성공하고 재고 0")
    void create_succeeds_whenQuantityEqualsStock() throws Exception {
        long product = createProduct("한정판", 1000, 3);

        postJson("/api/orders", orderBody(product, 3)).andExpect(status().isCreated());

        assertThat(stockOf(product)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{}",                                                                       // items 누락
            "{\"items\":[]}",                                                           // items 비어 있음
            "{\"items\":[null]}",                                                       // null 원소
            "{\"items\":[{\"quantity\":1}]}",                                           // productId 누락
            "{\"items\":[{\"productId\":1}]}",                                          // quantity 누락
            "{\"items\":[{\"productId\":1,\"quantity\":0}]}",                           // quantity < 1
            "{\"items\":[{\"productId\":1,\"quantity\":-3}]}",                          // quantity < 1
            "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}" // productId 중복
    })
    @DisplayName("R3 주문 생성: 규칙 위반은 400 (상품이 존재해도)")
    void create_returns400_whenInvalid(String body) throws Exception {
        createProduct("상품", 1000, 10); // id=1

        postJson("/api/orders", body).andExpect(problem(HttpStatus.BAD_REQUEST));

        assertThat(stockOf(1)).isEqualTo(10);
    }

    @Test
    @DisplayName("R3 주문 생성: 없는 상품은 404")
    void create_returns404_whenProductMissing() throws Exception {
        postJson("/api/orders", orderBody(999, 1)).andExpect(problem(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("R3 주문 생성: 재고 부족은 409")
    void create_returns409_whenStockInsufficient() throws Exception {
        long product = createProduct("상품", 1000, 2);

        postJson("/api/orders", orderBody(product, 3)).andExpect(problem(HttpStatus.CONFLICT));

        assertThat(stockOf(product)).isEqualTo(2);
    }

    @Test
    @DisplayName("R3 주문 생성: 한 항목이라도 재고가 부족하면 어떤 재고도 차감되지 않고 주문도 생기지 않는다")
    void create_isAllOrNothing() throws Exception {
        long enough = createProduct("충분", 1000, 10);
        long scarce = createProduct("부족", 1000, 1);

        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":5},{"productId":%d,"quantity":2}]}
                """.formatted(enough, scarce))
                .andExpect(problem(HttpStatus.CONFLICT));

        assertThat(stockOf(enough)).isEqualTo(10);
        assertThat(stockOf(scarce)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    @DisplayName("R3 주문 생성: 없는 상품과 재고 부족이 함께 있으면 404가 우선")
    void create_prefers404Over409() throws Exception {
        long scarce = createProduct("부족", 1000, 1);

        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":5},{"productId":999,"quantity":1}]}
                """.formatted(scarce))
                .andExpect(problem(HttpStatus.NOT_FOUND));

        assertThat(stockOf(scarce)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3 주문 생성: 400 사유와 없는 상품이 함께 있으면 400이 우선")
    void create_prefers400Over404() throws Exception {
        postJson("/api/orders", """
                {"items":[{"productId":999,"quantity":0}]}
                """)
                .andExpect(problem(HttpStatus.BAD_REQUEST));
    }
}
