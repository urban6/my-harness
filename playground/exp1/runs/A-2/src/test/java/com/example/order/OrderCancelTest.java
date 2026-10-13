package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("R5 주문 취소")
class OrderCancelTest extends IntegrationTestSupport {

    @Test
    void cancelsOrderAndRestoresStock() {
        long keyboard = createProduct("Keyboard", 30000, 5);
        long mouse = createProduct("Mouse", 10000, 10);
        long orderId = createOrderId(item(keyboard, 2), item(mouse, 3));
        assertThat(stockOf(keyboard)).isEqualTo(3);
        assertThat(stockOf(mouse)).isEqualTo(7);

        ResponseEntity<String> response = post("/api/orders/" + orderId + "/cancel", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("totalPrice").asLong()).isEqualTo(90000);
        assertThat(body.get("items")).hasSize(2);
        assertThat(json(get("/api/orders/" + orderId))).isEqualTo(body);

        assertThat(stockOf(keyboard)).isEqualTo(5);
        assertThat(stockOf(mouse)).isEqualTo(10);
    }

    @Test
    void returns409WhenAlreadyCancelledWithoutRestoringTwice() {
        long product = createProduct("A", 1000, 5);
        long orderId = createOrderId(item(product, 2));
        assertThat(post("/api/orders/" + orderId + "/cancel", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertProblem(post("/api/orders/" + orderId + "/cancel", null), HttpStatus.CONFLICT);
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(json(get("/api/orders/" + orderId)).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void returns404WhenMissing() {
        assertProblem(post("/api/orders/999999/cancel", null), HttpStatus.NOT_FOUND);
    }
}
