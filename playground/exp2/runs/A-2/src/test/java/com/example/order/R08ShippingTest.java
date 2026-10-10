package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R8 배송")
class R08ShippingTest extends IntegrationTest {

    private Resp ship(long id) {
        return api.post("/api/orders/" + id + "/ship", null);
    }

    private Resp deliver(long id) {
        return api.post("/api/orders/" + id + "/deliver", null);
    }

    @Test
    @DisplayName("R8.1 PAID → SHIPPED → DELIVERED")
    void shipAndDeliver() {
        long p = createProduct(1000, 10);
        long orderId = paidOrder("u1", null, p, 1);

        Resp shipped = ship(orderId);
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.json().path("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.json().path("id").asLong()).isEqualTo(orderId);

        Resp delivered = deliver(orderId);
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.json().path("status").asText()).isEqualTo("DELIVERED");
        assertThat(order(orderId)).isEqualTo(delivered.json());
        assertThat(product(p).path("stock").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R8.2 그 밖의 상태에서는 409, 없는 주문은 404")
    void invalidTransitions() {
        long p = createProduct(1000, 10);
        long pending = placeOrderOk("u1", null, p, 1);
        long paid = paidOrder("u1", null, p, 1);
        long delivered = paidOrder("u1", null, p, 1);
        ship(delivered);
        deliver(delivered);

        assertProblem(ship(pending), 409, "INVALID_STATE");
        assertProblem(deliver(pending), 409, "INVALID_STATE");
        assertProblem(deliver(paid), 409, "INVALID_STATE");
        assertProblem(ship(delivered), 409, "INVALID_STATE");
        assertProblem(deliver(delivered), 409, "INVALID_STATE");
        assertProblem(ship(55_555), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(55_555), 404, "ORDER_NOT_FOUND");
        assertThat(order(paid).path("status").asText()).isEqualTo("PAID");
    }
}
