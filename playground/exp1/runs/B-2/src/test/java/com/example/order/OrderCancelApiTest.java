package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OrderCancelApiTest extends IntegrationTest {

    @Test
    @DisplayName("R5 주문 취소: 200 + R4 형태 본문(status=CANCELLED), 주문 수량만큼 재고 복원")
    void cancel_returns200AndRestoresStock() throws Exception {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 2000, 5);
        long orderId = readId(postJson("/api/orders", """
                {"items":[{"productId":%d,"quantity":4},{"productId":%d,"quantity":5}]}
                """.formatted(a, b)).andExpect(status().isCreated()));
        assertThat(stockOf(a)).isEqualTo(6);
        assertThat(stockOf(b)).isZero();

        mvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalPrice").value(1000 * 4 + 2000 * 5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.createdAt").isString());

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(5);
        mvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("R5 주문 취소: 이미 취소된 주문은 409이고 재고를 다시 복원하지 않는다")
    void cancel_returns409_whenAlreadyCancelled() throws Exception {
        long product = createProduct("상품", 1000, 10);
        long orderId = createOrder(product, 3);
        mvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        mvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(problem(HttpStatus.CONFLICT));

        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("R5 주문 취소: 없으면 404")
    void cancel_returns404_whenMissing() throws Exception {
        mvc.perform(post("/api/orders/{id}/cancel", 999)).andExpect(problem(HttpStatus.NOT_FOUND));
    }
}
