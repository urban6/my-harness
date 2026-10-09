package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R7 취소·환불")
class CancelRefundApiTest extends IntegrationTest {

    private Response cancel(long orderId) {
        return api.post("/api/orders/" + orderId + "/cancel", null);
    }

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 취소 → 200 CANCELLED, 예약·쿠폰 사용 복원")
    void cancelPending() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 10);
        long orderId = createOrder(uniqueUser(), code, p, 3).get("id").asLong();

        Response r = cancel(orderId);

        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(r.body()).isEqualTo(order(orderId));
        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
    }

    @Test
    @DisplayName("R7.3 PAID 취소 → PG 환불 후 REFUNDED, stock 복원, 쿠폰 사용 복원")
    void refundPaid() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 10);
        JsonNode paid = paidOrder(uniqueUser(), code, "tok", p, 3);
        long orderId = paid.get("id").asLong();
        assertThat(product(p).get("stock").asLong()).isEqualTo(7);

        Response r = cancel(orderId);

        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(r.body().get("paidAt").asText()).isEqualTo(paid.get("paidAt").asText());
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
        assertThat(PG.refundCallsForOrder(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 결제 금액이 0원이던 주문은 PG 없이 환불된다")
    void refundZeroTotal() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100_000, 10);
        long orderId = paidOrder(uniqueUser(), code, "tok", p, 1).get("id").asLong();

        Response r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(product(p).get("stock").asLong()).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.3 PG 환불이 5xx면 503, 주문·재고·쿠폰 변화 없음")
    void refundGatewayError() {
        assertRefundUnavailable("refund-error-card");
    }

    @Test
    @DisplayName("R7.3 PG 환불이 2초를 넘기면 503, 주문·재고·쿠폰 변화 없음")
    void refundGatewayTimeout() {
        assertRefundUnavailable("refund-slow-card");
    }

    private void assertRefundUnavailable(String cardToken) {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 10);
        long orderId = paidOrder(uniqueUser(), code, cardToken, p, 3).get("id").asLong();

        assertProblem(cancel(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asLong()).isEqualTo(7);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태는 409 INVALID_STATE, R7.1 없으면 404")
    void invalidStates() {
        long p = createProduct(1000, 10);

        long cancelled = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        cancel(cancelled);
        assertProblem(cancel(cancelled), 409, "INVALID_STATE");

        long refunded = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        cancel(refunded);
        assertProblem(cancel(refunded), 409, "INVALID_STATE");

        long failed = createOrder(uniqueUser(), null, p, 1).get("id").asLong();
        pay(failed, uniqueKey(), "decline");
        assertProblem(cancel(failed), 409, "INVALID_STATE");

        long shipped = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        api.post("/api/orders/" + shipped + "/ship", null);
        assertProblem(cancel(shipped), 409, "INVALID_STATE");

        long delivered = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();
        api.post("/api/orders/" + delivered + "/ship", null);
        api.post("/api/orders/" + delivered + "/deliver", null);
        assertProblem(cancel(delivered), 409, "INVALID_STATE");

        assertProblem(cancel(987654321L), 404, "ORDER_NOT_FOUND");
    }
}
