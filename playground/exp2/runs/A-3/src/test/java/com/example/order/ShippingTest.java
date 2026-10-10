package com.example.order;

import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R8. 배송")
class ShippingTest extends IntegrationTest {

    private Resp ship(long id) {
        return post("/api/orders/" + id + "/ship", null);
    }

    private Resp deliver(long id) {
        return post("/api/orders/" + id + "/deliver", null);
    }

    @Test
    @DisplayName("R8.1 PAID → SHIPPED → DELIVERED, 둘 다 200 과 R3.5 형태 본문")
    void shipAndDeliver() {
        long productId = createProduct(1_000, 10);
        long orderId = paidOrder("tok_ok", item(productId, 1)).path("id").asLong();

        Resp shipped = ship(orderId);
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.json().path("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.json().path("id").asLong()).isEqualTo(orderId);
        assertThat(shipped.json().path("paidAt").isNull()).isFalse();
        assertThat(order(orderId)).isEqualTo(shipped.json());

        Resp delivered = deliver(orderId);
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.json().path("status").asText()).isEqualTo("DELIVERED");
        assertThat(order(orderId)).isEqualTo(delivered.json());
        assertThat(product(productId).path("stock").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R8.2 PAID 가 아닌 주문의 ship, SHIPPED 가 아닌 주문의 deliver 는 409 INVALID_STATE")
    void invalidTransitions() {
        long productId = createProduct(1_000, 10);
        long pending = placeOrder(item(productId, 1)).path("id").asLong();
        long paid = paidOrder("tok_ok", item(productId, 1)).path("id").asLong();

        assertProblem(ship(pending), 409, "INVALID_STATE");
        assertProblem(deliver(pending), 409, "INVALID_STATE");
        assertProblem(deliver(paid), 409, "INVALID_STATE");

        assertThat(ship(paid).status()).isEqualTo(200);
        assertProblem(ship(paid), 409, "INVALID_STATE");
        assertThat(deliver(paid).status()).isEqualTo(200);
        assertProblem(deliver(paid), 409, "INVALID_STATE");
        assertProblem(ship(paid), 409, "INVALID_STATE");
        assertThat(order(paid).path("status").asText()).isEqualTo("DELIVERED");
        assertThat(order(pending).path("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 없는 주문은 404 ORDER_NOT_FOUND")
    void notFound() {
        assertProblem(ship(987654321L), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(987654321L), 404, "ORDER_NOT_FOUND");
    }
}
