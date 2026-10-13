package com.example.order.orders;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("R4 주문 조회")
class OrderQueryApiTest extends IntegrationTest {

    @Test
    void get_returnsOrderWithItemsAndTotal() throws Exception {
        long a = createProduct("A", 1200, 10);
        long b = createProduct("B", 700, 10);
        JsonNode created = createOrder("""
                [{"productId":%d,"quantity":3},{"productId":%d,"quantity":4}]
                """.formatted(a, b));
        long id = created.get("id").asLong();

        String body = getJson("/api/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.totalPrice").value(1200 * 3 + 700 * 4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(a))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1200))
                .andExpect(jsonPath("$.items[1].productId").value(b))
                .andExpect(jsonPath("$.items[1].quantity").value(4))
                .andExpect(jsonPath("$.items[1].unitPrice").value(700))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*")))
                .andReturn().getResponse().getContentAsString();

        // 생성 응답(R3)과 조회 응답(R4)은 같은 형태·값이다.
        assertThat(objectMapper.readTree(body)).isEqualTo(created);
        assertThatCode(() -> Instant.parse(created.get("createdAt").asText())).doesNotThrowAnyException();
    }

    @Test
    void get_keepsUnitPriceAtOrderTime() throws Exception {
        long product = createProduct("A", 1000, 10);
        long id = createOrder("[{\"productId\":%d,\"quantity\":2}]".formatted(product)).get("id").asLong();

        // 주문 이후 상품 가격이 바뀌어도 주문의 단가·합계는 그대로다.
        jdbcTemplate.update("UPDATE products SET price = 9999 WHERE id = ?", product);

        getJson("/api/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.totalPrice").value(2000));
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        getJson("/api/orders/999").andExpect(problem(404));
    }
}
