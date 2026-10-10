package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** R8. 배송 */
class ShippingApiTest extends IntegrationTestSupport {

    @Test
    void paid_ship_thenDeliver() {
        long orderId = paidOrder(uniqueUser(), null, item(createProduct(1_000, 10), 1));

        Res shipped = post("/api/orders/" + orderId + "/ship");
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.body().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.body()).isEqualTo(order(orderId));

        Res delivered = post("/api/orders/" + orderId + "/deliver");
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.body().get("paidAt").isNull()).isFalse();
    }

    @Test
    void ship_fromNonPaid_returns409() {
        long p = createProduct(1_000, 10);
        long pending = placeOrder(uniqueUser(), null, item(p, 1));
        long shipped = paidOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + shipped + "/ship");

        assertProblem(post("/api/orders/" + pending + "/ship"), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + shipped + "/ship"), 409, "INVALID_STATE");
        assertThat(order(pending).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void deliver_fromNonShipped_returns409() {
        long p = createProduct(1_000, 10);
        long pending = placeOrder(uniqueUser(), null, item(p, 1));
        long paid = paidOrder(uniqueUser(), null, item(p, 1));
        long delivered = paidOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + delivered + "/ship");
        post("/api/orders/" + delivered + "/deliver");

        assertProblem(post("/api/orders/" + pending + "/deliver"), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + paid + "/deliver"), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + delivered + "/deliver"), 409, "INVALID_STATE");
        assertProblem(post("/api/orders/" + delivered + "/ship"), 409, "INVALID_STATE");
    }

    @Test
    void unknownOrder_returns404() {
        assertProblem(post("/api/orders/999999999/ship"), 404, "ORDER_NOT_FOUND");
        assertProblem(post("/api/orders/999999999/deliver"), 404, "ORDER_NOT_FOUND");
    }
}
