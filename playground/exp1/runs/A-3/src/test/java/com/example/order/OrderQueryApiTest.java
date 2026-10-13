package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R4 주문 조회")
class OrderQueryApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("200 {id, status, totalPrice, items[{productId, quantity, unitPrice}], createdAt}")
    void returnsOrder() {
        long a = createProduct("A", 1500, 10);
        long b = createProduct("B", 700, 10);
        Instant before = Instant.now().minusSeconds(1);
        long orderId = createOrderId(a, 3, b, 4);

        ResponseEntity<String> response = get("/api/orders/" + orderId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").isIntegralNumber()).isTrue();
        assertThat(body.get("totalPrice").asLong()).isEqualTo(3 * 1500 + 4 * 700);
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(a);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(1500);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(b);
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(4);
        assertThat(items.get(1).get("unitPrice").asLong()).isEqualTo(700);
        Instant createdAt = Instant.parse(body.get("createdAt").asText());
        assertThat(createdAt).isBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("unitPrice는 주문 시점의 상품 가격이며 이후 가격이 바뀌어도 유지된다")
    void unitPriceIsSnapshotAtOrderTime() {
        long product = createProduct("A", 1000, 10);
        long orderId = createOrderId(product, 2);

        jdbc.update("UPDATE products SET price = 5000 WHERE id = ?", product);

        JsonNode body = json(get("/api/orders/" + orderId));
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2000);
    }

    @Test
    @DisplayName("없는 주문이면 404")
    void notFound() {
        assertProblem(get("/api/orders/999999"), 404);
    }
}
