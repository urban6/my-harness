package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.order.support.IntegrationTest;

@DisplayName("R3 주문 생성")
class OrderCreateApiTest extends IntegrationTest {

    @Test
    void create_returns201_andDecreasesStock() throws Exception {
        long keyboard = createProduct("키보드", 50000, 10);
        long mouse = createProduct("마우스", 30000, 5);

        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":3}]}
                """.formatted(keyboard, mouse))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/orders/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.totalPrice").value(50000 * 2 + 30000 * 3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(keyboard))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(50000))
                .andExpect(jsonPath("$.items[1].productId").value(mouse))
                .andExpect(jsonPath("$.items[1].quantity").value(3))
                .andExpect(jsonPath("$.items[1].unitPrice").value(30000))
                .andExpect(jsonPath("$.createdAt").isString());

        assertThat(stockOf(keyboard)).isEqualTo(8);
        assertThat(stockOf(mouse)).isEqualTo(2);
    }

    @Test
    void create_allowsOrderingEntireStock() throws Exception {
        long product = createProduct("한정판", 1000, 3);

        createOrder("[{\"productId\":%d,\"quantity\":3}]".formatted(product));

        assertThat(stockOf(product)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",                                                     // items 누락
            "{\"items\":[]}",                                         // items 비어 있음
            "{\"items\":[null]}",                                     // null 항목
            "{\"items\":[{\"quantity\":1}]}",                         // productId 누락
            "{\"items\":[{\"productId\":1}]}",                        // quantity 누락
            "{\"items\":[{\"productId\":1,\"quantity\":0}]}",         // quantity < 1
            "{\"items\":[{\"productId\":1,\"quantity\":-3}]}",
            "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}" // productId 중복
    })
    void create_returns400_whenInvalid(String body) throws Exception {
        createProduct("상품", 1000, 10);

        postJson("/api/orders", body).andExpect(problem(400));

        assertThat(stockOf(1)).isEqualTo(10);
    }

    @Test
    void create_returns404_whenProductMissing() throws Exception {
        long product = createProduct("상품", 1000, 10);

        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":1},{"productId":999,"quantity":1}]}
                """.formatted(product))
                .andExpect(problem(404));

        assertThat(stockOf(product)).isEqualTo(10);
        assertThat(orderCount()).isZero();
    }

    @Test
    void create_returns409_andKeepsAllStock_whenAnyItemShort() throws Exception {
        long enough = createProduct("충분", 1000, 10);
        long shortage = createProduct("부족", 2000, 1);

        // 첫 항목은 충분하지만 둘째 항목이 부족 → 어떤 재고도 차감되지 않아야 한다.
        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":5},{"productId":%d,"quantity":2}]}
                """.formatted(enough, shortage))
                .andExpect(problem(409));

        assertThat(stockOf(enough)).isEqualTo(10);
        assertThat(stockOf(shortage)).isEqualTo(1);
        assertThat(orderCount()).isZero();
    }

    @Test
    void create_prefers400Over404And409() throws Exception {
        long shortage = createProduct("부족", 1000, 0);

        // 중복(400) + 없는 상품(404) + 재고 부족(409)이 섞이면 400
        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":1},{"productId":999,"quantity":1},{"productId":999,"quantity":1}]}
                """.formatted(shortage))
                .andExpect(problem(400));
    }

    @Test
    void create_prefers404Over409() throws Exception {
        long shortage = createProduct("부족", 1000, 0);

        // 재고 부족 항목이 먼저 나와도 없는 상품(404)이 우선
        postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":1},{"productId":999,"quantity":1}]}
                """.formatted(shortage))
                .andExpect(problem(404));
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }
}
