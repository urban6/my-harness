package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

class OrderQueryApiTest extends IntegrationTest {

    @Test
    @DisplayName("R4 주문 조회: 200 {id, status, totalPrice, items[{productId, quantity, unitPrice}], createdAt}")
    void get_returnsOrder() throws Exception {
        long a = createProduct("A", 1200, 10);
        long b = createProduct("B", 700, 10);
        long orderId = readId(postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":3},{"productId":%d,"quantity":4}]}
                """.formatted(a, b)).andExpect(status().isCreated()));

        String json = mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.totalPrice").value(1200 * 3 + 700 * 4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(a))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1200))
                .andExpect(jsonPath("$.items[1].productId").value(b))
                .andExpect(jsonPath("$.items[1].quantity").value(4))
                .andExpect(jsonPath("$.items[1].unitPrice").value(700))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "status", "totalPrice", "items", "createdAt");
        assertThat(node.get("createdAt").isTextual()).isTrue();
        assertThatCode(() -> Instant.parse(node.get("createdAt").asText())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R4 주문 조회: unitPrice는 주문 시점 가격이며 이후 가격 변경에 영향받지 않는다")
    void get_keepsUnitPriceAtOrderTime() throws Exception {
        long product = createProduct("상품", 5000, 10);
        long orderId = createOrder(product, 2);

        jdbc.update("UPDATE products SET price = 9999 WHERE id = ?", product);

        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(5000))
                .andExpect(jsonPath("$.totalPrice").value(10000));
    }

    @Test
    @DisplayName("R4 주문 조회: 없으면 404")
    void get_returns404_whenMissing() throws Exception {
        mvc.perform(get("/api/orders/{id}", 999)).andExpect(problem(HttpStatus.NOT_FOUND));
    }
}
