package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.FakePaymentGateway.RefundMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R7. 취소·환불")
class CancelRefundApiTest extends IntegrationTestSupport {

    private ResponseEntity<JsonNode> cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT → CANCELLED, 200 + 주문 본문, 예약·쿠폰 사용 복원")
    void cancelPending() {
        long p = createProduct(1_000, 10);
        String code = createCoupon();
        long orderId = placeOrder(newUser(), code, item(p, 4)).get("id").asLong();

        ResponseEntity<JsonNode> response = cancel(orderId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("id").asLong()).isEqualTo(orderId);
        assertThat(response.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(order(orderId)).isEqualTo(response.getBody());
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R7.3 PAID → PG 환불 성공 시 REFUNDED, stock이 수량만큼 늘고 쿠폰 사용 복원")
    void refundPaid() {
        long p = createProduct(1_000, 10);
        String code = createCoupon();
        String user = newUser();
        long orderId = paidOrder(user, code, item(p, 4)).get("id").asLong();
        assertThat(product(p).get("stock").asInt()).isEqualTo(6);

        ResponseEntity<JsonNode> response = cancel(orderId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(PG.refunds()).contains(PG.paymentIdFor(orderId));
        assertThat(product(p).get("stock").asInt()).isEqualTo(10);
        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(createOrder(user, newKey(), orderBody(code, item(p, 1))).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.3 PG 환불 요청은 결제 시 받은 paymentId로 한다")
    void refundUsesPaymentId() {
        long p = createProduct(1_000, 10);
        long orderId = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        String paymentId = PG.paymentIdFor(orderId);
        assertThat(paymentId).isNotNull();

        cancel(orderId);

        assertThat(PG.refunds()).containsOnlyOnce(paymentId);
    }

    @Test
    @DisplayName("R7.3 PG 5xx면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void refundGatewayError() {
        assertRefundFailureLeavesOrderUnchanged(RefundMode.ERROR);
    }

    @Test
    @DisplayName("R7.3 PG가 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰은 바뀌지 않는다")
    void refundGatewayTimeout() {
        assertRefundFailureLeavesOrderUnchanged(RefundMode.TIMEOUT);
    }

    private void assertRefundFailureLeavesOrderUnchanged(RefundMode mode) {
        long p = createProduct(1_000, 10);
        String code = createCoupon();
        long orderId = paidOrder(newUser(), code, item(p, 4)).get("id").asLong();

        PG.setRefundMode(mode);
        assertProblem(cancel(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(order(orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(product(p).get("stock").asInt()).isEqualTo(6);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

        PG.setRefundMode(RefundMode.OK);
        assertThat(cancel(orderId).getBody().get("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태(CANCELLED·REFUNDED·PAYMENT_FAILED·SHIPPED·DELIVERED)는 409 INVALID_STATE")
    void invalidStates() {
        long p = createProduct(1_000, 20);

        long cancelled = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        cancel(cancelled);
        assertProblem(cancel(cancelled), 409, "INVALID_STATE");

        long refunded = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        cancel(refunded);
        assertProblem(cancel(refunded), 409, "INVALID_STATE");

        long failed = placeOrder(newUser(), null, item(p, 1)).get("id").asLong();
        pay(failed, newKey(), "decline-card");
        assertProblem(cancel(failed), 409, "INVALID_STATE");

        long shipped = paidOrder(newUser(), null, item(p, 1)).get("id").asLong();
        post("/api/orders/" + shipped + "/ship", null);
        assertProblem(cancel(shipped), 409, "INVALID_STATE");

        post("/api/orders/" + shipped + "/deliver", null);
        assertProblem(cancel(shipped), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R7.1 없는 주문은 404")
    void notFound() {
        assertProblem(cancel(999_999_999L), 404, "ORDER_NOT_FOUND");
    }
}
