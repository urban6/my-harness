package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R8 배송")
class ShippingApiTest extends IntegrationTest {

    private Response ship(long id) {
        return api.post("/api/orders/" + id + "/ship", null);
    }

    private Response deliver(long id) {
        return api.post("/api/orders/" + id + "/deliver", null);
    }

    @Test
    @DisplayName("R8.1 PAID → SHIPPED → DELIVERED, 둘 다 200 + 주문 본문")
    void shipAndDeliver() {
        long p = createProduct(1000, 10);
        long orderId = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();

        Response shipped = ship(orderId);
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.body().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.body()).isEqualTo(order(orderId));

        Response delivered = deliver(orderId);
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.body().get("paidAt").isNull()).isFalse();
        assertThat(product(p).get("stock").asLong()).isEqualTo(9);
    }

    @Test
    @DisplayName("R8.2 그 밖의 상태에서는 409 INVALID_STATE")
    void invalidStates() {
        long p = createProduct(1000, 10);
        long pending = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        assertProblem(ship(pending), 409, "INVALID_STATE");
        assertProblem(deliver(pending), 409, "INVALID_STATE");

        long paid = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        assertProblem(deliver(paid), 409, "INVALID_STATE");
        ship(paid);
        assertProblem(ship(paid), 409, "INVALID_STATE");
        deliver(paid);
        assertProblem(deliver(paid), 409, "INVALID_STATE");
        assertProblem(ship(paid), 409, "INVALID_STATE");

        long cancelled = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        api.post("/api/orders/" + cancelled + "/cancel", null);
        assertProblem(ship(cancelled), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 없는 주문은 404 ORDER_NOT_FOUND")
    void notFound() {
        assertProblem(ship(987654321L), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(987654321L), 404, "ORDER_NOT_FOUND");
    }
}
