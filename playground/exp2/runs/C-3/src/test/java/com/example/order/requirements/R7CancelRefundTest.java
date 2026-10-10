package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R7. 취소·환불")
class R7CancelRefundTest extends IntegrationTestBase {

    private long pendingOrder(long productId, int quantity) {
        return orderIdOk(uid("u"), orderBody(null, item(productId, quantity)));
    }

    private long paidOrder(long productId, int quantity) {
        long orderId = pendingOrder(productId, quantity);
        payOk(orderId);
        return orderId;
    }

    // ---------------------------------------------------------------- R7.1 / R7.2

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 주문을 취소하면 200, 본문은 R3.5 형태이고 status=CANCELLED")
    void r7_2_cancelPending_returns200Cancelled() {
        long productId = product(1_000, 5);
        long orderId = pendingOrder(productId, 2);

        ResponseEntity<String> r = cancel(orderId);

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode o = json(r);
        assertThat(o.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(o.get("id").asLong()).isEqualTo(orderId);
        assertThat(o.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("R7.2 취소하면 예약이 복원되고 stock 은 그대로다")
    void r7_2_cancelPending_restoresReservation() {
        long p1 = product(1_000, 5);
        long p2 = product(1_000, 8);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(p1, 2), item(p2, 3)));

        cancel(orderId);

        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(stockOf(p1)).isEqualTo(5);
        assertThat(stockOf(p2)).isEqualTo(8);
    }

    @Test
    @DisplayName("R7.2 취소하면 쿠폰 사용이 복원된다")
    void r7_2_cancelPending_restoresCouponUsage() {
        long productId = product(10_000, 5);
        String code = uniqueCode("CNLC");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        assertThat(usedCountOf(code)).isEqualTo(1L);

        cancel(orderId);

        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R7.2 PENDING_PAYMENT 취소는 PG 를 호출하지 않는다")
    void r7_2_cancelPending_doesNotCallPg() {
        long productId = product(1_000, 5);
        long orderId = pendingOrder(productId, 1);

        cancel(orderId);

        assertThat(PG.paymentCalls()).isZero();
        assertThat(PG.refundCalls()).isZero();
    }

    @Test
    @DisplayName("R7.2 취소로 풀린 재고는 다른 주문이 바로 사용할 수 있다")
    void r7_2_cancelPending_freesStockForOthers() {
        long productId = product(1_000, 1);
        long first = pendingOrder(productId, 1);
        assertProblem(createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1))), 409, "INSUFFICIENT_STOCK");

        cancel(first);

        assertThat(statusOf(createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1))))).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.1 없는 주문은 404 ORDER_NOT_FOUND")
    void r7_1_cancelUnknownOrder_returns404() {
        assertProblem(cancel(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R7.1 주문 id 가 숫자가 아니면 400")
    void r7_1_cancelNonNumericId_returns400() {
        assertProblem(post("/api/orders/abc/cancel", null), 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R7.3 (환불)

    @Test
    @DisplayName("R7.3 PAID 주문을 취소하면 PG 환불을 요청하고 200 REFUNDED")
    void r7_3_cancelPaid_callsPgRefundAndReturnsRefunded() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 2);

        ResponseEntity<String> r = cancel(orderId);

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("REFUNDED");
        assertThat(PG.refundCalls()).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("R7.3 환불은 결제 때 PG 가 돌려준 paymentId 로 요청한다 (스텁은 결제 키마다 고유 paymentId 를 발급)")
    void r7_3_refund_usesPaymentIdFromPayment() {
        long p1 = product(10_000, 5);
        long first = orderIdOk(uid("u"), orderBody(null, item(p1, 1)));
        long second = orderIdOk(uid("u"), orderBody(null, item(p1, 1)));
        payOk(first);
        payOk(second);

        cancel(second);

        // 두 번째 결제의 paymentId(pay_<n+1>) 로 환불해야 한다 - 첫 번째 결제의 id 가 아니다
        assertThat(PG.refundedPaymentIds()).hasSize(1);
        int firstSeq = Integer.parseInt(PG.refundedPaymentIds().get(0).substring("pay_".length())) - 1;
        cancel(first);
        assertThat(PG.refundedPaymentIds()).hasSize(2);
        assertThat(PG.refundedPaymentIds().get(1)).isEqualTo("pay_" + firstSeq);
    }

    @Test
    @DisplayName("R7.3 환불되면 각 상품의 stock 이 주문 수량만큼 늘어 원래대로 돌아온다")
    void r7_3_refund_restoresStock() {
        long p1 = product(1_000, 10);
        long p2 = product(1_000, 10);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(p1, 3), item(p2, 4)));
        payOk(orderId);
        assertThat(stockOf(p1)).isEqualTo(7);
        assertThat(stockOf(p2)).isEqualTo(6);

        cancel(orderId);

        assertThat(stockOf(p1)).isEqualTo(10);
        assertThat(stockOf(p2)).isEqualTo(10);
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(availableOf(p1)).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.3 환불되면 쿠폰 사용이 복원된다")
    void r7_3_refund_restoresCouponUsage() {
        long productId = product(10_000, 5);
        String code = uniqueCode("RFDC");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        payOk(orderId);
        assertThat(usedCountOf(code)).isEqualTo(1L);

        cancel(orderId);

        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R7.3 PG 환불이 5xx 이면 503 이고 주문·재고·쿠폰은 바뀌지 않는다")
    void r7_3_refund5xx_returns503_andKeepsState() {
        long productId = product(10_000, 5);
        String code = uniqueCode("RF5X");
        createCoupon(code, "FIXED", 1_000, 0, null, 3);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 2)));
        payOk(orderId);
        PG.failRefundsWith5xx();

        ResponseEntity<String> r = cancel(orderId);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.refundCalls()).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(reservedOf(productId)).isZero();
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R7.3 PG 환불 연결에 실패하면 503 이고 PAID 로 남는다")
    void r7_3_refundConnectionRefused_returns503() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 1);
        PG.stop();

        ResponseEntity<String> r = cancel(orderId);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    @Test
    @DisplayName("R7.3 PG 환불 응답이 2초를 넘으면 약 2초 만에 503 이고 상태는 불변이다")
    void r7_3_refundTimeout_returns503Within2s() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 1);
        PG.delayRefunds(6_000);
        long startedAt = System.nanoTime();

        ResponseEntity<String> r = cancel(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(4_500));
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1_800));
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    @Test
    @DisplayName("R7.3 PG 가 환불 응답 헤더만 먼저 보내고 본문을 2초 넘게 멈추면 약 2초 만에 503 이고 상태는 불변이다")
    void r7_3_refundBodyStall_returns503Within2s() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 1);
        PG.stallRefundBodies(7_000);
        long startedAt = System.nanoTime();

        ResponseEntity<String> r = cancel(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(4_500));
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    @Test
    @DisplayName("R7.3 환불 장애 뒤 복구되면 같은 주문을 다시 취소해 환불할 수 있다")
    void r7_3_afterRefundRecovery_cancelSucceeds() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 1);
        PG.failRefundsWith5xx();
        cancel(orderId);
        PG.approveRefunds();

        ResponseEntity<String> r = cancel(orderId);

        assertThat(statusOf(r)).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("REFUNDED");
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("R7.3 이미 환불된 주문을 다시 취소하면 409 이고 PG 환불은 한 번만 호출된다")
    void r7_3_doubleCancel_refundsOnlyOnce() {
        long productId = product(10_000, 5);
        long orderId = paidOrder(productId, 1);
        cancel(orderId);

        ResponseEntity<String> second = cancel(orderId);

        assertProblem(second, 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    // ---------------------------------------------------------------- R7.4 (그 밖의 상태)

    @Test
    @DisplayName("R7.4 이미 CANCELLED 인 주문을 취소하면 409 INVALID_STATE")
    void r7_4_cancelCancelled_returns409() {
        long productId = product(1_000, 5);
        long orderId = pendingOrder(productId, 1);
        cancel(orderId);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R7.4 PAYMENT_FAILED 주문을 취소하면 409 INVALID_STATE")
    void r7_4_cancelPaymentFailed_returns409() {
        long productId = product(1_000, 5);
        long orderId = pendingOrder(productId, 1);
        PG.declinePayments();
        pay(orderId, uid("pk"), "tok");

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isZero();
    }

    @Test
    @DisplayName("R7.4 SHIPPED 주문을 취소하면 409 INVALID_STATE 이고 환불되지 않는다")
    void r7_4_cancelShipped_returns409() {
        long productId = product(1_000, 5);
        long orderId = paidOrder(productId, 1);
        ship(orderId);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("R7.4 DELIVERED 주문을 취소하면 409 INVALID_STATE 이고 환불되지 않는다")
    void r7_4_cancelDelivered_returns409() {
        long productId = product(1_000, 5);
        long orderId = paidOrder(productId, 1);
        ship(orderId);
        deliver(orderId);

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("DELIVERED");
        assertThat(stockOf(productId)).isEqualTo(4);
    }

    @Test
    @DisplayName("R7.4 취소된 주문의 재고 수치는 409 응답으로 두 번 복원되지 않는다")
    void r7_4_repeatedCancel_doesNotRestoreTwice() {
        long productId = product(1_000, 5);
        long first = pendingOrder(productId, 2);
        pendingOrder(productId, 1);
        cancel(first);

        List<ResponseEntity<String>> repeated = List.of(cancel(first), cancel(first));

        assertThat(repeated).allSatisfy(r -> assertProblem(r, 409, "INVALID_STATE"));
        assertThat(reservedOf(productId)).isEqualTo(1);
    }
}
