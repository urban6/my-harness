package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("R4 주문 조회")
class OrderQueryTest extends IntegrationTestSupport {

    @Test
    void returnsOrderWithItemsAndTotal() {
        long keyboard = createProduct("Keyboard", 30000, 5);
        long mouse = createProduct("Mouse", 12500, 10);
        Instant before = Instant.now().minusSeconds(1);
        long orderId = createOrderId(item(keyboard, 2), item(mouse, 3));

        ResponseEntity<String> response = get("/api/orders/" + orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("ORDERED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2 * 30000 + 3 * 12500);

        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(keyboard);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(30000);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(mouse);
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(3);
        assertThat(items.get(1).get("unitPrice").asLong()).isEqualTo(12500);

        assertThat(body.get("createdAt").isTextual()).isTrue();
        Instant createdAt = Instant.parse(body.get("createdAt").asText());
        assertThat(createdAt).isBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    void keepsUnitPriceAtOrderTime() {
        long product = createProduct("Keyboard", 30000, 5);
        long orderId = createOrderId(item(product, 2));

        // There is no product update API, so change the price directly.
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", 99000, product);

        JsonNode body = json(get("/api/orders/" + orderId));
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(30000);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(60000);
    }

    @Test
    void returns404WhenMissing() {
        assertProblem(get("/api/orders/999999"), HttpStatus.NOT_FOUND);
    }
}
