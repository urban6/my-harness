package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** R8. 배송 상태 전이. */
class OrderShippingTest extends IntegrationTestBase {

    private long pendingOrder() {
        return newOrder(newProduct(1000, 10), 1).get("id").asLong();
    }

    private long paidOrder() {
        long id = pendingOrder();
        payOk(id);
        return id;
    }

    private long shippedOrder() {
        long id = paidOrder();
        assertStatus(ship(id), 200);
        return id;
    }

    private long deliveredOrder() {
        long id = shippedOrder();
        assertStatus(deliver(id), 200);
        return id;
    }

    private void assertInvalidState(ResponseEntity<JsonNode> res, long orderId, String expectedStatusAfter) {
        assertProblem(res, 409, "INVALID_STATE");
        assertThat(statusOf(orderId)).isEqualTo(expectedStatusAfter);
    }

    @Test
    @DisplayName("R8.1 ship: PAID -> SHIPPED, 200 + 주문 형태 본문, 재고는 그대로")
    void r8_1_shipPaidOrder() {
        long productId = newProduct(1000, 10);
        long orderId = newOrder(productId, 2).get("id").asLong();
        payOk(orderId);

        ResponseEntity<JsonNode> res = ship(orderId);

        assertStatus(res, 200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(res.getBody().get("id").asLong()).isEqualTo(orderId);
        assertThat(res.getBody().get("paidAt").isNull()).isFalse();
        assertThat(order(orderId)).isEqualTo(res.getBody());
        assertStock(productId, 8, 0);
    }

    @Test
    @DisplayName("R8.1 deliver: SHIPPED -> DELIVERED, 200 + 주문 형태 본문")
    void r8_1_deliverShippedOrder() {
        long orderId = shippedOrder();

        ResponseEntity<JsonNode> res = deliver(orderId);

        assertStatus(res, 200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(order(orderId)).isEqualTo(res.getBody());
    }

    @Test
    @DisplayName("R8.2 ship 은 PENDING_PAYMENT 에서 409 INVALID_STATE")
    void r8_2_shipPendingRejected() {
        long id = pendingOrder();

        assertInvalidState(ship(id), id, "PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 ship 은 CANCELLED 에서 409 INVALID_STATE")
    void r8_2_shipCancelledRejected() {
        long id = pendingOrder();
        cancel(id);

        assertInvalidState(ship(id), id, "CANCELLED");
    }

    @Test
    @DisplayName("R8.2 ship 은 PAYMENT_FAILED 에서 409 INVALID_STATE")
    void r8_2_shipPaymentFailedRejected() {
        long id = pendingOrder();
        pay(id, uniqueKey(), "decline_card");

        assertInvalidState(ship(id), id, "PAYMENT_FAILED");
    }

    @Test
    @DisplayName("R8.2 ship 은 REFUNDED 에서 409 INVALID_STATE")
    void r8_2_shipRefundedRejected() {
        long id = paidOrder();
        cancel(id);

        assertInvalidState(ship(id), id, "REFUNDED");
    }

    @Test
    @DisplayName("R8.2 ship 은 이미 SHIPPED 에서 409 INVALID_STATE")
    void r8_2_shipShippedRejected() {
        long id = shippedOrder();

        assertInvalidState(ship(id), id, "SHIPPED");
    }

    @Test
    @DisplayName("R8.2 ship 은 DELIVERED 에서 409 INVALID_STATE")
    void r8_2_shipDeliveredRejected() {
        long id = deliveredOrder();

        assertInvalidState(ship(id), id, "DELIVERED");
    }

    @Test
    @DisplayName("R8.2 deliver 는 PENDING_PAYMENT 에서 409 INVALID_STATE")
    void r8_2_deliverPendingRejected() {
        long id = pendingOrder();

        assertInvalidState(deliver(id), id, "PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 deliver 는 PAID(배송 전)에서 409 INVALID_STATE")
    void r8_2_deliverPaidRejected() {
        long id = paidOrder();

        assertInvalidState(deliver(id), id, "PAID");
    }

    @Test
    @DisplayName("R8.2 deliver 는 CANCELLED / PAYMENT_FAILED / REFUNDED 에서 409 INVALID_STATE")
    void r8_2_deliverTerminalStatesRejected() {
        long cancelled = pendingOrder();
        cancel(cancelled);
        long failed = pendingOrder();
        pay(failed, uniqueKey(), "decline_card");
        long refunded = paidOrder();
        cancel(refunded);

        assertInvalidState(deliver(cancelled), cancelled, "CANCELLED");
        assertInvalidState(deliver(failed), failed, "PAYMENT_FAILED");
        assertInvalidState(deliver(refunded), refunded, "REFUNDED");
    }

    @Test
    @DisplayName("R8.2 deliver 는 이미 DELIVERED 에서 409 INVALID_STATE")
    void r8_2_deliverDeliveredRejected() {
        long id = deliveredOrder();

        assertInvalidState(deliver(id), id, "DELIVERED");
    }

    @Test
    @DisplayName("R8.2 없는 주문의 ship / deliver 는 404 ORDER_NOT_FOUND")
    void r8_2_unknownOrderReturns404() {
        for (Function<Long, ResponseEntity<JsonNode>> action : java.util.List.<Function<Long, ResponseEntity<JsonNode>>>of(
                this::ship, this::deliver)) {
            assertProblem(action.apply(999_999_999L), 404, "ORDER_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("R8.2 orderId 가 숫자가 아니면 400")
    void r8_2_nonNumericOrderIdRejected() {
        assertProblem(post("/api/orders/abc/ship", null), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders/abc/deliver", null), 400, "VALIDATION_ERROR");
    }
}
