package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

@DisplayName("R5 주문 취소")
class OrderCancelApiTest extends IntegrationTest {

    @Test
    void cancel_returns200_andRestoresStock() throws Exception {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 500, 4);
        JsonNode created = createOrder("""
                [{"productId":%d,"quantity":3},{"productId":%d,"quantity":4}]
                """.formatted(a, b));
        long id = created.get("id").asLong();
        assertThat(stockOf(a)).isEqualTo(7);
        assertThat(stockOf(b)).isZero();

        postJson("/api/orders/" + id + "/cancel", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalPrice").value(5000))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()));

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(4);
        getJson("/api/orders/" + id).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_returns409_whenAlreadyCancelled_andDoesNotRestoreTwice() throws Exception {
        long a = createProduct("A", 1000, 10);
        long id = createOrder("[{\"productId\":%d,\"quantity\":3}]".formatted(a)).get("id").asLong();
        postJson("/api/orders/" + id + "/cancel", "").andExpect(status().isOk());

        postJson("/api/orders/" + id + "/cancel", "").andExpect(problem(409));

        assertThat(stockOf(a)).isEqualTo(10);
    }

    @Test
    void cancel_returns404_whenMissing() throws Exception {
        postJson("/api/orders/999/cancel", "").andExpect(problem(404));
    }
}
