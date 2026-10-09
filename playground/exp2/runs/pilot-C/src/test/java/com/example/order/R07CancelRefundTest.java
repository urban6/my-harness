package com.example.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R7 취소·환불")
class R07CancelRefundTest extends AbstractIntegrationTest {

    /** 고유 paymentId 로 승인 스텁을 걸고 결제까지 마친 PAID 주문 (환불 스텁은 호출자가 건다). */
    private ApiResponse paidWith(String paymentId, String coupon, long... productIdQty) {
        ApiResponse order = newOrder(uniqueUser(), coupon, productIdQty);
        stubPgPayment("APPROVED", paymentId);
        ApiResponse paid = pay(order.id(), uniqueKey(), "tok");
        assertThat(paid.status()).isEqualTo(200);
        return paid;
    }

    // ------------------------------------------------------------- R7.2 pending

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 취소 -> 200 CANCELLED, R3.5 형태, 예약·쿠폰 복원, PG 미호출")
    void cancelPending_restores() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p1, 3, p2, 2);

        ApiResponse r = cancel(order.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.json("status").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(order.id())).isEqualTo("CANCELLED");
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(stockOf(p1)).isEqualTo(10);
        assertThat(usedCountOf(coupon)).isZero();
        assertThat(pgPaymentRequestCount()).isZero();
        assertThat(pgRefundRequestCount()).isZero();
    }

    @Test
    @DisplayName("R7.2 취소한 재고는 다시 주문할 수 있다")
    void cancelPending_stockOrderableAgain() {
        long p = newProduct(1_000, 2);
        ApiResponse order = newOrder(uniqueUser(), null, p, 2);
        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1).status()).isEqualTo(409);

        cancel(order.id());

        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 2).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------- R7.3 refund

    @Test
    @DisplayName("R7.3 PAID 취소 -> PG 환불 호출(결제 승인 paymentId) 후 200 REFUNDED, stock 증가, 쿠폰 복원")
    void cancelPaid_refunds() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        String paymentId = "pay-" + uniqueKey();
        ApiResponse paid = paidWith(paymentId, coupon, p, 4);
        assertThat(stockOf(p)).isEqualTo(6);
        stubPgRefund(paymentId);

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("status").asText()).isEqualTo("REFUNDED");
        assertThat(statusOf(paid.id())).isEqualTo("REFUNDED");
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments/" + paymentId + "/refund")));
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R7.3 환불된 주문의 재고는 다시 주문 가능하다")
    void cancelPaid_stockOrderableAgain() {
        long p = newProduct(1_000, 3);
        String paymentId = "pay-" + uniqueKey();
        ApiResponse paid = paidWith(paymentId, null, p, 3);
        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1).status()).isEqualTo(409);
        stubPgRefund(paymentId);

        cancel(paid.id());

        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 3).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.3 환불 PG 500 -> 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문 PAID·재고·쿠폰 불변")
    void refund_pg500_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse paid = paidWith("pay-" + uniqueKey(), coupon, p, 4);
        stubPgRefundStatus(500);

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(r.isProblemJson()).isTrue();
        assertThat(statusOf(paid.id())).isEqualTo("PAID");
        assertThat(stockOf(p)).isEqualTo(6);
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 환불 PG 연결 실패 -> 503, 불변")
    void refund_connectionFault_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse paid = paidWith("pay-" + uniqueKey(), coupon, p, 4);
        stubPg(WireMock.post(urlPathMatching("/v1/payments/.+/refund"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(statusOf(paid.id())).isEqualTo("PAID");
        assertThat(stockOf(p)).isEqualTo(6);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 환불 PG 2초 초과(6초 지연) -> 503, 불변")
    void refund_timeout_503_unchanged() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        String paymentId = "pay-" + uniqueKey();
        ApiResponse paid = paidWith(paymentId, coupon, p, 4);
        stubPg(WireMock.post(urlEqualTo("/v1/payments/" + paymentId + "/refund")).willReturn(aResponse()
                .withStatus(200).withFixedDelay(6_000).withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + paymentId + "\",\"status\":\"REFUNDED\"}")));
        long start = System.nanoTime();

        ApiResponse r = cancel(paid.id());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(5_000));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(statusOf(paid.id())).isEqualTo("PAID");
        assertThat(stockOf(p)).isEqualTo(6);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 환불 장애 후 PG 복구되면 다시 취소해 REFUNDED 가능")
    void refund_retryAfterRecovery() {
        long p = newProduct(1_000, 10);
        String paymentId = "pay-" + uniqueKey();
        ApiResponse paid = paidWith(paymentId, null, p, 4);
        stubPgRefundStatus(503);
        assertThat(cancel(paid.id()).status()).isEqualTo(503);
        WIREMOCK.resetAll();
        stubPgRefund(paymentId);

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("status").asText()).isEqualTo("REFUNDED");
        assertThat(stockOf(p)).isEqualTo(10);
    }

    // ------------------------------------------------------------- R7.4 / 404

    @Test
    @DisplayName("R7.4 이미 CANCELLED 인 주문 재취소 -> 409 INVALID_STATE")
    void cancel_alreadyCancelled_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        cancel(order.id());

        ApiResponse r = cancel(order.id());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    @DisplayName("R7.4 PAYMENT_FAILED 주문 취소 -> 409")
    void cancel_paymentFailed_409() {
        long p = newProduct(1_000, 10);
        ApiResponse order = newOrder(uniqueUser(), null, p, 1);
        stubPgPayment("DECLINED", "pay-no");
        pay(order.id(), uniqueKey(), "tok");

        ApiResponse r = cancel(order.id());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        assertThat(statusOf(order.id())).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    @DisplayName("R7.4 REFUNDED 주문 재취소 -> 409, 환불 중복 호출 없음, stock 이중 증가 없음")
    void cancel_alreadyRefunded_409() {
        long p = newProduct(1_000, 10);
        String paymentId = "pay-" + uniqueKey();
        ApiResponse paid = paidWith(paymentId, null, p, 4);
        stubPgRefund(paymentId);
        cancel(paid.id());

        ApiResponse r = cancel(paid.id());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/payments/" + paymentId + "/refund")));
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.4 SHIPPED / DELIVERED 주문 취소 -> 409, 환불 호출 없음")
    void cancel_shippedOrDelivered_409() {
        long p = newProduct(1_000, 10);
        ApiResponse shipped = newPaidOrder(uniqueUser(), null, p, 1);
        ApiResponse delivered = newPaidOrder(uniqueUser(), null, p, 1);
        ship(shipped.id());
        ship(delivered.id());
        deliver(delivered.id());

        ApiResponse r1 = cancel(shipped.id());
        ApiResponse r2 = cancel(delivered.id());

        assertThat(r1.status()).isEqualTo(409);
        assertThat(r1.code()).isEqualTo("INVALID_STATE");
        assertThat(r2.status()).isEqualTo(409);
        assertThat(r2.code()).isEqualTo("INVALID_STATE");
        assertThat(pgRefundRequestCount()).isZero();
        assertThat(statusOf(shipped.id())).isEqualTo("SHIPPED");
        assertThat(statusOf(delivered.id())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R7.1 없는 주문 취소 -> 404 ORDER_NOT_FOUND")
    void cancel_unknown_404() {
        ApiResponse r = cancel(Long.MAX_VALUE - 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R7.1 숫자가 아닌 id -> 400")
    void cancel_nonNumericId_400() {
        assertThat(post("/api/orders/abc/cancel", null, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R7.2 동시에 같은 PENDING 주문을 취소하면 1건만 200, 복원은 1회")
    void cancel_concurrent_restoresOnce() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 3);
        ApiResponse order = newOrder(uniqueUser(), coupon, p, 4);
        var tasks = java.util.stream.IntStream.range(0, 6)
                .<java.util.concurrent.Callable<ApiResponse>>mapToObj(i -> () -> cancel(order.id())).toList();

        var rs = runConcurrently(tasks);

        assertThat(countStatus(rs, 200)).isEqualTo(1);
        assertThat(countCode(rs, 409, "INVALID_STATE")).isEqualTo(5);
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
    }
}
