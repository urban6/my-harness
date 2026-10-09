package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R8 배송")
class R8ShippingTest extends IntegrationTestBase {

    private long paidOrder(long productId) {
        long id = orderOk("u1", null, productId, 1).get("id").asLong();
        payOk(id);
        return id;
    }

    private long product() {
        return createProduct("상품", 1000, 50).get("id").asLong();
    }

    @Test
    @DisplayName("R8.1 PAID -> ship 200 SHIPPED (R3.5 형태), 재고 불변")
    void r8_1_ship() {
        long p = product();
        long id = paidOrder(p);

        ResponseEntity<JsonNode> res = ship(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(res.getBody().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(res.getBody().get("paidAt").isNull()).isFalse();
        assertThat(statusOf(id)).isEqualTo("SHIPPED");
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(49);
        assertThat(prod.get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R8.1 SHIPPED -> deliver 200 DELIVERED (R3.5 형태)")
    void r8_1_deliver() {
        long id = paidOrder(product());
        ship(id);

        ResponseEntity<JsonNode> res = deliver(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("id").asLong()).isEqualTo(id);
        assertThat(res.getBody().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 PENDING_PAYMENT 주문 ship/deliver -> 409 INVALID_STATE")
    void r8_2_pending() {
        long id = orderOk("u1", null, product(), 1).get("id").asLong();

        assertProblem(ship(id), 409, "INVALID_STATE");
        assertProblem(deliver(id), 409, "INVALID_STATE");
        assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 PAID 주문을 바로 deliver -> 409 INVALID_STATE")
    void r8_2_deliverPaid() {
        long id = paidOrder(product());

        assertProblem(deliver(id), 409, "INVALID_STATE");
        assertThat(statusOf(id)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R8.2 SHIPPED 주문을 다시 ship -> 409, DELIVERED 주문 ship/deliver -> 409")
    void r8_2_repeatedTransitions() {
        long id = paidOrder(product());
        ship(id);
        assertProblem(ship(id), 409, "INVALID_STATE");
        deliver(id);

        assertProblem(ship(id), 409, "INVALID_STATE");
        assertProblem(deliver(id), 409, "INVALID_STATE");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 CANCELLED / PAYMENT_FAILED / REFUNDED 주문 ship/deliver -> 409 INVALID_STATE")
    void r8_2_terminalStates() {
        long p = product();
        long cancelled = orderOk("u1", null, p, 1).get("id").asLong();
        cancel(cancelled);
        long failed = orderOk("u2", null, p, 1).get("id").asLong();
        PG.decline();
        payOrder(failed, key(), "tok");
        PG.approve();
        long refunded = paidOrder(p);
        cancel(refunded);

        for (long id : new long[] {cancelled, failed, refunded}) {
            assertProblem(ship(id), 409, "INVALID_STATE");
            assertProblem(deliver(id), 409, "INVALID_STATE");
        }
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(failed)).isEqualTo("PAYMENT_FAILED");
        assertThat(statusOf(refunded)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("R8.2 없는 주문 ship/deliver -> 404 ORDER_NOT_FOUND")
    void r8_2_notFound() {
        assertProblem(ship(9999), 404, "ORDER_NOT_FOUND");
        assertProblem(deliver(9999), 404, "ORDER_NOT_FOUND");
    }
}
