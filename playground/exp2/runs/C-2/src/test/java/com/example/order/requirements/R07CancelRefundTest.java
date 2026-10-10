package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R7. 취소·환불 (PG 연결 실패는 GatewayConnectionFailureTest, 만료된 주문 취소는 R06ExpirationTest) */
class R07CancelRefundTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R7.1 취소는 200이고 본문은 R3.5 형태이며 이후 조회와 같다 (헤더·본문 불필요)")
    void r7_1_cancel_returnsOrderShape() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();

        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(getOrder(orderId).json()).isEqualTo(r.json());
    }

    @Test
    @DisplayName("R7.1 없는 주문 취소는 404 ORDER_NOT_FOUND이다")
    void r7_1_cancel_unknown_returns404() {
        assertProblem(cancel(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R7.2 PENDING_PAYMENT 취소는 CANCELLED가 되고 모든 상품의 예약과 쿠폰 사용이 복원된다")
    void r7_2_cancelPending_restoresReservationAndCoupon() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(p1, 3), line(p2, 4)).id();

        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("CANCELLED");
        assertThat(reserved(p1)).isZero();
        assertThat(reserved(p2)).isZero();
        assertThat(stock(p1)).isEqualTo(10);
        assertThat(stock(p2)).isEqualTo(10);
        assertThat(usedCount(coupon)).isZero();
        assertThat(PG.refundCalls()).isEmpty();
    }

    @Test
    @DisplayName("R7.3 PAID 취소는 PG에 환불을 요청하고, 성공하면 REFUNDED, stock이 늘고 쿠폰 사용이 복원된다")
    void r7_3_cancelPaid_refundsAndRestores() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();
        pay(orderId);
        assertThat(stock(productId)).isEqualTo(6);
        assertThat(usedCount(coupon)).isEqualTo(1);

        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("REFUNDED");
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(stock(productId)).isEqualTo(10);
        assertThat(reserved(productId)).isZero();
        assertThat(available(productId)).isEqualTo(10);
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R7.3 환불 요청은 결제 때 PG가 발급한 paymentId로 한 번 보낸다")
    void r7_3_refund_usesPaymentIdFromPayment() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        String key = uniqueKey();
        pay(orderId, key, "tok");
        String issued = PG.paymentIdForKey(key);

        cancel(orderId);

        assertThat(issued).isNotBlank();
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(PG.refundCalls().get(0).paymentId()).isEqualTo(issued);
    }

    @Test
    @DisplayName("R7.3 환불된 주문(REFUNDED)의 paidAt은 유지된다")
    void r7_3_refunded_keepsPaidAt() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        ApiResponse paid = pay(orderId);

        ApiResponse refunded = cancel(orderId);

        assertThat(instant(refunded, "paidAt")).isEqualTo(instant(paid, "paidAt"));
    }

    @Test
    @DisplayName("R7.3 환불 후 복원된 쿠폰·재고는 다시 주문에 쓸 수 있다")
    void r7_3_afterRefund_couponAndStockReusable() {
        long productId = newProduct(1_000, 2);
        String coupon = newCoupon("FIXED", 100, 0, null, 1);
        String user = uniqueUser();
        ApiResponse first = postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 2)));
        pay(first.id());
        assertThat(placeOrder(null, line(productId, 1)).code()).isEqualTo("INSUFFICIENT_STOCK");

        cancel(first.id());

        assertThat(postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 2))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.3 PG 환불이 5xx이면 503이고 주문(PAID)·재고·쿠폰은 바뀌지 않으며, 복구 후 다시 환불할 수 있다")
    void r7_3_refund5xx_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();
        pay(orderId);
        PG.refund500();

        ApiResponse r = cancel(orderId);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(getOrder(orderId).text("status")).isEqualTo("PAID");
        assertThat(stock(productId)).isEqualTo(6);
        assertThat(reserved(productId)).isZero();
        assertThat(usedCount(coupon)).isEqualTo(1);

        PG.refundOk();
        assertThat(cancel(orderId).text("status")).isEqualTo("REFUNDED");
        assertThat(stock(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.3 PG 환불이 2초 안에 응답하지 않으면(지연 3000ms) 약 2초 뒤 503이고 주문·재고·쿠폰은 바뀌지 않는다")
    void r7_3_refundTimeout_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();
        pay(orderId);
        PG.delayRefund(3_000);

        long start = System.nanoTime();
        ApiResponse r = cancel(orderId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsedMs).isBetween(1_800L, 2_900L);
        assertThat(getOrder(orderId).text("status")).isEqualTo("PAID");
        assertThat(stock(productId)).isEqualTo(6);
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @ParameterizedTest(name = "R7.4 {0} 주문은 취소할 수 없다")
    @ValueSource(strings = {"CANCELLED", "PAYMENT_FAILED", "SHIPPED", "DELIVERED", "REFUNDED"})
    @DisplayName("R7.4 취소·환불 대상이 아닌 상태는 409 INVALID_STATE이고 PG를 호출하지 않으며 재고·상태가 변하지 않는다")
    void r7_4_otherStates_return409(String status) {
        long orderId = newOrderInStatus(status);
        PG.reset();
        int stockBefore = stockOfOrder(orderId);

        ApiResponse r = cancel(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isEmpty();
        assertThat(getOrder(orderId).text("status")).isEqualTo(status);
        assertThat(stockOfOrder(orderId)).isEqualTo(stockBefore);
    }

    private int stockOfOrder(long orderId) {
        long productId = getOrder(orderId).json().get("items").get(0).get("productId").asLong();
        return stock(productId);
    }
}
