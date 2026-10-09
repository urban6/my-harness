package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R8. 배송")
class ShippingApiTest extends IntegrationTestSupport {

    private ResponseEntity<JsonNode> ship(long id) {
        return post("/api/orders/" + id + "/ship", null);
    }

    private ResponseEntity<JsonNode> deliver(long id) {
        return post("/api/orders/" + id + "/deliver", null);
    }

    @Test
    @DisplayName("R8.1 PAID → ship → SHIPPED → deliver → DELIVERED, 둘 다 200 + 주문 본문")
    void shipAndDeliver() {
        long p = createProduct(1_000, 10);
        long orderId = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();

        ResponseEntity<JsonNode> shipped = ship(orderId);
        assertThat(shipped.getStatusCode().value()).isEqualTo(200);
        assertThat(shipped.getBody().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(order(orderId)).isEqualTo(shipped.getBody());

        ResponseEntity<JsonNode> delivered = deliver(orderId);
        assertThat(delivered.getStatusCode().value()).isEqualTo(200);
        assertThat(delivered.getBody().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(order(orderId)).isEqualTo(delivered.getBody());
    }

    @Test
    @DisplayName("R8.2 그 밖의 상태에서 호출하면 409 INVALID_STATE")
    void invalidStates() {
        long p = createProduct(1_000, 10);

        long pending = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(ship(pending), 409, "INVALID_STATE");
        assertProblem(deliver(pending), 409, "INVALID_STATE");

        long paid = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        assertProblem(deliver(paid), 409, "INVALID_STATE");

        ship(paid);
        assertProblem(ship(paid), 409, "INVALID_STATE");

        deliver(paid);
        assertProblem(deliver(paid), 409, "INVALID_STATE");
        assertProblem(ship(paid), 409, "INVALID_STATE");

        long cancelled = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        post("/api/orders/" + cancelled + "/cancel", null);
        assertProblem(ship(cancelled), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 없는 주문은 404")
    void notFound() {
        assertProblem(ship(999_999_999L), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(999_999_999L), 404, "ORDER_NOT_FOUND");
    }
}
