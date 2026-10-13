package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R5 주문 취소")
class OrderCancelApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("200, 주문 조회와 같은 형태(status=CANCELLED)로 응답하고 재고를 복원한다")
    void cancelsOrderAndRestoresStock() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 2000, 5);
        long orderId = createOrderId(a, 3, b, 5);
        assertThat(stockOf(a)).isEqualTo(7);
        assertThat(stockOf(b)).isZero();

        ResponseEntity<String> response = post("/api/orders/" + orderId + "/cancel");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(3 * 1000 + 5 * 2000);
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("createdAt").isTextual()).isTrue();
        assertThat(json(get("/api/orders/" + orderId))).isEqualTo(body);

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(5);
    }

    @Test
    @DisplayName("이미 취소된 주문이면 409이고 재고를 다시 복원하지 않는다")
    void alreadyCancelled() {
        long product = createProduct("A", 1000, 10);
        long orderId = createOrderId(product, 4);
        assertThat(post("/api/orders/" + orderId + "/cancel").getStatusCode().value()).isEqualTo(200);

        assertProblem(post("/api/orders/" + orderId + "/cancel"), 409);
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("없는 주문이면 404")
    void notFound() {
        assertProblem(post("/api/orders/999999/cancel"), 404);
    }
}
