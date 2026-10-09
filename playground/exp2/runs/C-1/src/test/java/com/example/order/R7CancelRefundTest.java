package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R7 취소·환불")
class R7CancelRefundTest extends IntegrationTestBase {

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    // ---------------- R7.1 / R7.2 ----------------

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 취소 -> 200 CANCELLED(R3.5 형태), 예약·쿠폰 복원, stock 불변, PG 미호출")
    void r7_2_cancelPending() {
        long p1 = product(1000, 10);
        long p2 = product(2000, 10);
        createCoupon("CANCEL01", "FIXED", 100);
        long id = orderOk("u1", "CANCEL01", p1, 3, p2, 2).get("id").asLong();

        ResponseEntity<JsonNode> res = cancel(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode b = res.getBody();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(b.get("id").asLong()).isEqualTo(id);
        assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(getProduct(p1).get("reserved").asInt()).isZero();
        assertThat(getProduct(p2).get("reserved").asInt()).isZero();
        assertThat(getProduct(p1).get("stock").asInt()).isEqualTo(10);
        assertThat(getCoupon("CANCEL01").get("usedCount").asInt()).isZero();
        assertThat(PG.refundCallCount()).isZero();
        assertThat(PG.payCallCount()).isZero();
    }

    @Test
    @DisplayName("R7.1 없는 주문 취소 -> 404 ORDER_NOT_FOUND")
    void r7_1_notFound() {
        assertProblem(cancel(9999), 404, "ORDER_NOT_FOUND");
    }

    // ---------------- R7.3 ----------------

    @Test
    @DisplayName("R7.3 PAID 취소 -> PG 환불 호출, 200 REFUNDED, stock 이 주문 수량만큼 늘고 쿠폰 사용 복원")
    void r7_3_refundPaid() {
        long p1 = product(1000, 10);
        long p2 = product(2000, 10);
        createCoupon("REFUND01", "FIXED", 100);
        long id = orderOk("u1", "REFUND01", p1, 3, p2, 2).get("id").asLong();
        payOk(id);
        assertThat(getProduct(p1).get("stock").asInt()).isEqualTo(7);
        assertThat(getProduct(p2).get("stock").asInt()).isEqualTo(8);

        ResponseEntity<JsonNode> res = cancel(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(statusOf(id)).isEqualTo("REFUNDED");
        assertThat(PG.refundCallCount()).isEqualTo(1);
        JsonNode a = getProduct(p1);
        assertThat(a.get("stock").asInt()).isEqualTo(10);
        assertThat(a.get("reserved").asInt()).isZero();
        assertThat(a.get("available").asInt()).isEqualTo(10);
        assertThat(getProduct(p2).get("stock").asInt()).isEqualTo(10);
        assertThat(getCoupon("REFUND01").get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R7.3 환불은 결제 때 PG 가 준 paymentId 로 POST /v1/payments/{paymentId}/refund")
    void r7_3_refundUsesPaymentId() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 1).get("id").asLong();
        payOk(id);

        cancel(id);

        var refund = PG.lastRefundRequest();
        assertThat(refund).isNotNull();
        assertThat(refund.method()).isEqualTo("POST");
        // 가짜 PG 는 첫 결제에 pay_1 을 발급한다
        assertThat(refund.path()).isEqualTo("/v1/payments/pay_1/refund");
    }

    @Test
    @DisplayName("R7.3 PG 환불 5xx -> 503, 주문 PAID·재고·쿠폰 불변")
    void r7_3_refund5xx() {
        long p = product(1000, 10);
        createCoupon("REFUND02", "FIXED", 100);
        long id = orderOk("u1", "REFUND02", p, 2).get("id").asLong();
        payOk(id);
        PG.fail5xx();

        ResponseEntity<JsonNode> res = cancel(id);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(statusOf(id)).isEqualTo("PAID");
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
        assertThat(getCoupon("REFUND02").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 PG 연결 불가 -> 503, 주문·재고·쿠폰 불변")
    void r7_3_refundUnreachable() {
        long p = product(1000, 10);
        createCoupon("REFUND03", "FIXED", 100);
        long id = orderOk("u1", "REFUND03", p, 2).get("id").asLong();
        payOk(id);
        PG.unreachable();

        assertProblem(cancel(id), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(statusOf(id)).isEqualTo("PAID");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(8);
        assertThat(getCoupon("REFUND03").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 PG 환불이 2초 안에 응답하지 않으면 503, 주문·재고·쿠폰 불변")
    void r7_3_refundTimeout() throws Exception {
        long p = product(1000, 10);
        createCoupon("REFUND04", "FIXED", 100);
        long id = orderOk("u1", "REFUND04", p, 2).get("id").asLong();
        payOk(id);
        PG.delay(3500);

        long start = System.nanoTime();
        ResponseEntity<JsonNode> res = cancel(id);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(3300));
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1800));
        assertThat(statusOf(id)).isEqualTo("PAID");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(8);
        assertThat(getCoupon("REFUND04").get("usedCount").asInt()).isEqualTo(1);
        Thread.sleep(1700);
    }

    @Test
    @DisplayName("R7.3 환불 503 이후 PG 가 복구되면 다시 취소할 수 있다")
    void r7_3_retryAfter503() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 2).get("id").asLong();
        payOk(id);
        PG.fail5xx();
        assertProblem(cancel(id), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.approve();
        ResponseEntity<JsonNode> res = cancel(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.3 totalPrice 0 으로 결제된(PG 미경유) 주문도 취소하면 REFUNDED, stock 복원")
    void r7_3_refundZeroTotalOrder() {
        long p = product(1000, 10);
        createCoupon("REFUND05", "RATE", 100);
        long id = orderOk("u1", "REFUND05", p, 2).get("id").asLong();
        payOk(id);
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(8);

        ResponseEntity<JsonNode> res = cancel(id);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(10);
        assertThat(getCoupon("REFUND05").get("usedCount").asInt()).isZero();
    }

    // ---------------- R7.4 ----------------

    @Test
    @DisplayName("R7.4 이미 CANCELLED 인 주문을 다시 취소 -> 409 INVALID_STATE, 예약이 두 번 복원되지 않는다")
    void r7_4_cancelTwice() {
        long p = product(1000, 10);
        long first = orderOk("u1", null, p, 2).get("id").asLong();
        orderOk("u2", null, p, 3);
        cancel(first);

        assertProblem(cancel(first), 409, "INVALID_STATE");

        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("R7.4 PAYMENT_FAILED 주문 취소 -> 409 INVALID_STATE")
    void r7_4_cancelPaymentFailed() {
        long p = product(1000, 10);
        long id = orderOk("u1", null, p, 2).get("id").asLong();
        PG.decline();
        payOrder(id, key(), "tok");

        assertProblem(cancel(id), 409, "INVALID_STATE");

        assertThat(statusOf(id)).isEqualTo("PAYMENT_FAILED");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R7.4 SHIPPED / DELIVERED / REFUNDED 주문 취소 -> 409 INVALID_STATE, 환불 호출 없음, 재고 불변")
    void r7_4_cancelShippedDeliveredRefunded() {
        long p = product(1000, 20);
        long shipped = orderOk("u1", null, p, 1).get("id").asLong();
        payOk(shipped);
        ship(shipped);
        long delivered = orderOk("u2", null, p, 1).get("id").asLong();
        payOk(delivered);
        ship(delivered);
        deliver(delivered);
        long refunded = orderOk("u3", null, p, 1).get("id").asLong();
        payOk(refunded);
        cancel(refunded);
        int refundCalls = PG.refundCallCount();
        int stockBefore = getProduct(p).get("stock").asInt();

        assertProblem(cancel(shipped), 409, "INVALID_STATE");
        assertProblem(cancel(delivered), 409, "INVALID_STATE");
        assertProblem(cancel(refunded), 409, "INVALID_STATE");

        assertThat(PG.refundCallCount()).isEqualTo(refundCalls);
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(stockBefore);
        assertThat(statusOf(shipped)).isEqualTo("SHIPPED");
        assertThat(statusOf(delivered)).isEqualTo("DELIVERED");
        assertThat(statusOf(refunded)).isEqualTo("REFUNDED");
    }
}
