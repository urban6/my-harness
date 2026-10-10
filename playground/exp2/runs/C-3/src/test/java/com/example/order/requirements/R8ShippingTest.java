package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R8. 배송")
class R8ShippingTest extends IntegrationTestBase {

    private long pendingOrder(long productId, int quantity) {
        return orderIdOk(uid("u"), orderBody(null, item(productId, quantity)));
    }

    private long paidOrder(long productId, int quantity) {
        long orderId = pendingOrder(productId, quantity);
        payOk(orderId);
        return orderId;
    }

    // ---------------------------------------------------------------- R8.1

    @Test
    @DisplayName("R8.1 PAID 주문을 ship 하면 200, 본문은 R3.5 형태이고 status=SHIPPED")
    void r8_1_ship_paidToShipped() {
        long productId = product(1_000, 5);
        long orderId = paidOrder(productId, 1);

        ResponseEntity<String> r = ship(orderId);

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode o = json(r);
        assertThat(o.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(o.get("status").asText()).isEqualTo("SHIPPED");
        assertThat(o.get("paidAt").isNull()).isFalse();
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("R8.1 SHIPPED 주문을 deliver 하면 200 DELIVERED")
    void r8_1_deliver_shippedToDelivered() {
        long productId = product(1_000, 5);
        long orderId = paidOrder(productId, 1);
        ship(orderId);

        ResponseEntity<String> r = deliver(orderId);

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(orderStatus(orderId)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.1 ship/deliver 는 재고 수치와 PG 호출에 영향을 주지 않는다")
    void r8_1_shipAndDeliver_doNotTouchStockOrPg() {
        long productId = product(1_000, 5);
        long orderId = paidOrder(productId, 2);
        int paymentCalls = PG.paymentCalls();

        ship(orderId);
        deliver(orderId);

        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(reservedOf(productId)).isZero();
        assertThat(PG.paymentCalls()).isEqualTo(paymentCalls);
        assertThat(PG.refundCalls()).isZero();
    }

    // ---------------------------------------------------------------- R8.2

    @Test
    @DisplayName("R8.2 PENDING_PAYMENT 주문을 ship 하면 409 INVALID_STATE")
    void r8_2_shipPending_returns409() {
        long orderId = pendingOrder(product(1_000, 5), 1);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R8.2 이미 SHIPPED 인 주문을 다시 ship 하면 409")
    void r8_2_shipTwice_returns409() {
        long orderId = paidOrder(product(1_000, 5), 1);
        ship(orderId);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 DELIVERED 주문을 ship 하면 409")
    void r8_2_shipDelivered_returns409() {
        long orderId = paidOrder(product(1_000, 5), 1);
        ship(orderId);
        deliver(orderId);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
        assertThat(orderStatus(orderId)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 CANCELLED 주문을 ship 하면 409")
    void r8_2_shipCancelled_returns409() {
        long orderId = pendingOrder(product(1_000, 5), 1);
        cancel(orderId);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 REFUNDED 주문을 ship 하면 409")
    void r8_2_shipRefunded_returns409() {
        long orderId = paidOrder(product(1_000, 5), 1);
        cancel(orderId);

        assertProblem(ship(orderId), 409, "INVALID_STATE");
        assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("R8.2 PAYMENT_FAILED 주문을 ship 하면 409")
    void r8_2_shipPaymentFailed_returns409() {
        long orderId = pendingOrder(product(1_000, 5), 1);
        PG.declinePayments();
        pay(orderId, uid("pk"), "tok");

        assertProblem(ship(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 PAID 주문을 바로 deliver 하면 409 (SHIPPED 를 거쳐야 한다)")
    void r8_2_deliverPaid_returns409() {
        long orderId = paidOrder(product(1_000, 5), 1);

        assertProblem(deliver(orderId), 409, "INVALID_STATE");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R8.2 PENDING_PAYMENT 주문을 deliver 하면 409")
    void r8_2_deliverPending_returns409() {
        long orderId = pendingOrder(product(1_000, 5), 1);

        assertProblem(deliver(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 이미 DELIVERED 인 주문을 다시 deliver 하면 409")
    void r8_2_deliverTwice_returns409() {
        long orderId = paidOrder(product(1_000, 5), 1);
        ship(orderId);
        deliver(orderId);

        assertProblem(deliver(orderId), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R8.2 없는 주문을 ship 하면 404 ORDER_NOT_FOUND")
    void r8_2_shipUnknown_returns404() {
        assertProblem(ship(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R8.2 없는 주문을 deliver 하면 404 ORDER_NOT_FOUND")
    void r8_2_deliverUnknown_returns404() {
        assertProblem(deliver(987_654_321L), 404, "ORDER_NOT_FOUND");
    }
}
