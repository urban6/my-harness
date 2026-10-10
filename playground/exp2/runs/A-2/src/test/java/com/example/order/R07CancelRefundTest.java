package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.FakePaymentGateway.Behavior;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R7 취소·환불")
class R07CancelRefundTest extends IntegrationTest {

    private Resp cancel(long orderId) {
        return api.post("/api/orders/" + orderId + "/cancel", null);
    }

    @Test
    @DisplayName("R7.1·R7.2 PENDING_PAYMENT 취소는 200 CANCELLED, 예약·쿠폰 사용 복원")
    void cancelPending() {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("CXL1", "FIXED", 100));
        long orderId = placeOrderOk("u1", "CXL1", p, 3);

        Resp resp = cancel(orderId);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(resp.json().path("id").asLong()).isEqualTo(orderId);
        assertThat(order(orderId)).isEqualTo(resp.json());
        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(product(p).path("stock").asInt()).isEqualTo(10);
        assertThat(coupon("CXL1").path("usedCount").asInt()).isZero();
        assertThat(PG.refundRequests()).isEmpty();
    }

    @Test
    @DisplayName("R7.3 PAID 취소는 PG 환불 후 REFUNDED, stock 증가, 쿠폰 사용 복원")
    void refundPaid() {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("RFD1", "FIXED", 100));
        long orderId = paidOrder("u1", "RFD1", p, 3);
        assertThat(product(p).path("stock").asInt()).isEqualTo(7);

        Resp resp = cancel(orderId);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json().path("status").asText()).isEqualTo("REFUNDED");
        assertThat(product(p).path("stock").asInt()).isEqualTo(10);
        assertThat(product(p).path("reserved").asInt()).isZero();
        assertThat(coupon("RFD1").path("usedCount").asInt()).isZero();
        assertThat(PG.refundRequests()).hasSize(1);
        assertThat(PG.refundRequests().get(0)).startsWith("pay_");
        assertThat(placeOrder("u1", "RFD1", p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.3 0원 주문 환불은 PG 없이 REFUNDED")
    void refundZeroTotal() {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("ALLFREE", "RATE", 100));
        long orderId = paidOrder("u1", "ALLFREE", p, 2);

        Resp resp = cancel(orderId);

        assertThat(resp.json().path("status").asText()).isEqualTo("REFUNDED");
        assertThat(product(p).path("stock").asInt()).isEqualTo(10);
        assertThat(PG.refundRequests()).isEmpty();
    }

    @Test
    @DisplayName("R7.3 환불 중 PG 5xx면 503, 주문·재고·쿠폰은 그대로")
    void refundGatewayError() {
        assertRefundFailureLeavesStateUnchanged(() -> PG.refundBehavior(Behavior.SERVER_ERROR));
    }

    @Test
    @DisplayName("R7.3 환불 중 PG 연결 실패면 503")
    void refundConnectionFailure() {
        assertRefundFailureLeavesStateUnchanged(() -> PG.refundBehavior(Behavior.DROP_CONNECTION));
    }

    @Test
    @DisplayName("R7.3 환불 중 PG가 2초를 넘기면 503")
    void refundTimeout() {
        assertRefundFailureLeavesStateUnchanged(() -> PG.refundDelayMs(3000));
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태는 409, 없는 주문은 404")
    void invalidStates() {
        long p = createProduct(1000, 10);
        long cancelled = placeOrderOk("u1", null, p, 1);
        cancel(cancelled);
        long refunded = paidOrder("u1", null, p, 1);
        cancel(refunded);
        long shipped = paidOrder("u1", null, p, 1);
        api.post("/api/orders/" + shipped + "/ship", null);
        long failed = placeOrderOk("u1", null, p, 1);
        PG.paymentBehavior(Behavior.DECLINE);
        pay(failed);

        assertProblem(cancel(cancelled), 409, "INVALID_STATE");
        assertProblem(cancel(refunded), 409, "INVALID_STATE");
        assertProblem(cancel(shipped), 409, "INVALID_STATE");
        assertProblem(cancel(failed), 409, "INVALID_STATE");
        assertProblem(cancel(31_337), 404, "ORDER_NOT_FOUND");
    }

    private void assertRefundFailureLeavesStateUnchanged(Runnable breakGateway) {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("KEEPIT", "FIXED", 100));
        long orderId = paidOrder("u1", "KEEPIT", p, 2);
        JsonNode before = order(orderId);
        breakGateway.run();

        assertProblem(cancel(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(p).path("stock").asInt()).isEqualTo(8);
        assertThat(coupon("KEEPIT").path("usedCount").asInt()).isEqualTo(1);

        PG.reset();
        assertThat(cancel(orderId).json().path("status").asText()).isEqualTo("REFUNDED");
    }
}
