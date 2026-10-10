package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.example.order.FakePaymentGateway.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** R7. 취소·환불 */
class CancelApiTest extends IntegrationTestSupport {

    @Test
    void pending_isCancelled_andRestoresReservationAndCoupon() {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of());
        long orderId = placeOrder(uniqueUser(), code, item(p, 3));

        Res res = post("/api/orders/" + orderId + "/cancel");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.body()).isEqualTo(order(orderId));
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(PG.refunds()).isEmpty();
    }

    @Test
    void paid_isRefunded_andRestocksAndRestoresCoupon() {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of());
        String user = uniqueUser();
        long orderId = paidOrder(user, code, item(p, 3));
        assertThat(product(p).get("stock").asInt()).isEqualTo(7);

        Res res = post("/api/orders/" + orderId + "/cancel");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(PG.refunds()).hasSize(1).first().asString().startsWith("pay-");
        assertThat(createOrder(user, code, item(p, 1)).status()).isEqualTo(201);
    }

    @Test
    void paidWithZeroTotal_isRefundedWithoutGateway() {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 5_000));
        long orderId = paidOrder(uniqueUser(), code, item(p, 1));
        PG.refundMode(Mode.SERVER_ERROR);

        Res res = post("/api/orders/" + orderId + "/cancel");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(PG.refunds()).isEmpty();
    }

    @Test
    void refundGatewayServerError_returns503_andChangesNothing() {
        assertRefundFailureChangesNothing(Mode.SERVER_ERROR);
    }

    @Test
    void refundGatewayConnectionFailure_returns503_andChangesNothing() {
        assertRefundFailureChangesNothing(Mode.DROP_CONNECTION);
    }

    @Test
    void refundGatewayTimeout_returns503_andChangesNothing() {
        assertRefundFailureChangesNothing(Mode.SLOW);
    }

    private void assertRefundFailureChangesNothing(Mode mode) {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of());
        long orderId = paidOrder(uniqueUser(), code, item(p, 3));
        JsonNode before = order(orderId);
        PG.refundMode(mode);

        assertProblem(post("/api/orders/" + orderId + "/cancel"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(p).get("stock").asInt()).isEqualTo(7);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void otherStates_return409InvalidState() {
        long p = createProduct(1_000, 100);
        long cancelled = placeOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + cancelled + "/cancel");
        long shipped = paidOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + shipped + "/ship");
        long delivered = paidOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + delivered + "/ship");
        post("/api/orders/" + delivered + "/deliver");
        long refunded = paidOrder(uniqueUser(), null, item(p, 1));
        post("/api/orders/" + refunded + "/cancel");
        long failed = placeOrder(uniqueUser(), null, item(p, 1));
        PG.paymentMode(Mode.DECLINE);
        pay(failed, uniqueKey());

        for (long orderId : new long[] {cancelled, shipped, delivered, refunded, failed}) {
            assertProblem(post("/api/orders/" + orderId + "/cancel"), 409, "INVALID_STATE");
        }
    }

    @Test
    void unknownOrder_returns404() {
        assertProblem(post("/api/orders/999999999/cancel"), 404, "ORDER_NOT_FOUND");
    }
}
