package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R6. 결제 만료 (ORDER_PAYMENT_TTL=PT2S 컨텍스트) + 만료와 맞물린 R5.2 / R7.4 / R8.2 */
class R06ExpirationTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", PG::baseUrl);
        registry.add("order.payment-ttl", () -> "PT2S");
    }

    private static void sleepUntil(Instant instant) throws InterruptedException {
        long ms = Duration.between(Instant.now(), instant).toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    @Test
    @DisplayName("R6.1 + R6.2 PENDING_PAYMENT 주문은 expiresAt + 2초 안에 EXPIRED가 되고 상품 reserved·쿠폰 usedCount가 복원된다")
    void r6_1_r6_2_expiredWithin2Seconds_restoresReservationAndCoupon() throws Exception {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 3);
        ApiResponse created = placeOrder(coupon, line(p1, 4), line(p2, 2));
        assertThat(created.status()).isEqualTo(201);
        Instant expiresAt = instant(created, "expiresAt");
        assertThat(Duration.between(instant(created, "createdAt"), expiresAt)).isEqualTo(Duration.ofSeconds(2));
        assertThat(reserved(p1)).isEqualTo(4);
        assertThat(usedCount(coupon)).isEqualTo(1);

        sleepUntil(expiresAt.plusSeconds(2));

        assertThat(getOrder(created.id()).text("status")).isEqualTo("EXPIRED");
        assertThat(reserved(p1)).isZero();
        assertThat(reserved(p2)).isZero();
        assertThat(available(p1)).isEqualTo(10);
        assertThat(stock(p1)).isEqualTo(10);
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R6.2 한꺼번에 만든 여러 주문이 모두 expiresAt + 2초 안에 만료 반영된다")
    void r6_2_manyOrders_allExpiredWithin2Seconds() throws Exception {
        long productId = newProduct(1_000, 100);
        List<Long> ids = new ArrayList<>();
        Instant lastExpiresAt = Instant.EPOCH;
        for (int i = 0; i < 8; i++) {
            ApiResponse r = placeOrderOk(productId, 1);
            ids.add(r.id());
            lastExpiresAt = instant(r, "expiresAt");
        }
        assertThat(reserved(productId)).isEqualTo(8);

        sleepUntil(lastExpiresAt.plusSeconds(2));

        for (long id : ids) {
            assertThat(getOrder(id).text("status")).as("order %d", id).isEqualTo("EXPIRED");
        }
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R6.1 + R2.6 만료로 복원된 쿠폰은 같은 사용자가 다시 쓸 수 있다")
    void r6_1_expiredOrder_releasesCouponForSameUser() throws Exception {
        String coupon = newCoupon("FIXED", 100, 0, null, 1);
        long productId = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse first = postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 1)));
        assertThat(postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 1))).code())
                .isEqualTo("COUPON_NOT_APPLICABLE");

        sleepUntil(instant(first, "expiresAt").plusSeconds(2));

        ApiResponse again = postOrder(user, uniqueKey(), orderJson(coupon, line(productId, 1)));
        assertThat(again.status()).isEqualTo(201);
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.2 expiresAt이 지난 주문은 아직 EXPIRED로 바뀌기 전이어도 결제가 409 INVALID_STATE이고 PG를 호출하지 않는다")
    void r5_2_payAfterExpiresAt_returns409() throws Exception {
        ApiResponse created = placeOrderOk(newProduct(1_000, 10), 1);
        sleepUntil(instant(created, "expiresAt").plusMillis(30));

        ApiResponse r = pay(created.id());

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
    }

    @Test
    @DisplayName("R5.2 EXPIRED 주문 결제는 409 INVALID_STATE이다")
    void r5_2_payExpiredOrder_returns409() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        awaitOrderStatus(orderId, "EXPIRED", Duration.ofSeconds(8));

        ApiResponse r = pay(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(getOrder(orderId).text("status")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("R7.4 + R8.2 EXPIRED 주문의 취소·배송·배송완료는 409 INVALID_STATE이고 복원이 다시 일어나지 않는다")
    void r7_4_r8_2_expiredOrder_rejectsFurtherTransitions() {
        long productId = newProduct(1_000, 10);
        placeOrderOk(productId, 3);
        long orderId = placeOrderOk(productId, 2).id();
        awaitOrderStatus(orderId, "EXPIRED", Duration.ofSeconds(8));

        assertProblem(cancel(orderId), 409, "INVALID_STATE");
        assertProblem(ship(orderId), 409, "INVALID_STATE");
        assertProblem(deliver(orderId), 409, "INVALID_STATE");

        awaitReserved(productId, 0);
        assertThat(stock(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R6.1 결제 완료(PAID)된 주문은 expiresAt이 지나도 만료되지 않는다")
    void r6_1_paidOrder_isNotExpired() throws Exception {
        long productId = newProduct(1_000, 10);
        ApiResponse created = placeOrderOk(productId, 2);
        ApiResponse paid = pay(created.id());
        assertThat(paid.status()).as("expiresAt 이전에 결제가 끝나야 하는 테스트: %s", paid).isEqualTo(200);

        sleepUntil(instant(created, "expiresAt").plusSeconds(3));

        assertThat(getOrder(created.id()).text("status")).isEqualTo("PAID");
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R6.1 expiresAt 전에 취소된 주문은 만료 시점에 다시 복원되지 않는다 (reserved가 음수·어긋남 없이 0)")
    void r6_1_cancelledOrder_isNotRestoredTwice() throws Exception {
        long productId = newProduct(1_000, 10);
        ApiResponse cancelled = placeOrderOk(productId, 3);
        ApiResponse pending = placeOrderOk(productId, 2);
        assertThat(cancel(cancelled.id()).text("status")).isEqualTo("CANCELLED");
        assertThat(reserved(productId)).isEqualTo(2);

        sleepUntil(instant(pending, "expiresAt").plusSeconds(2));

        assertThat(getOrder(pending.id()).text("status")).isEqualTo("EXPIRED");
        assertThat(getOrder(cancelled.id()).text("status")).isEqualTo("CANCELLED");
        assertThat(reserved(productId)).isZero();
        assertThat(stock(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("R6.1 PG 장애(503)로 결제에 실패한 주문도 expiresAt이 지나면 만료되어 복원된다")
    void r6_1_orderWithFailedPaymentAttempt_stillExpires() throws Exception {
        long productId = newProduct(1_000, 10);
        ApiResponse created = placeOrderOk(productId, 2);
        PG.http500();
        assertThat(pay(created.id()).status()).isEqualTo(503);

        sleepUntil(instant(created, "expiresAt").plusSeconds(2));

        assertThat(getOrder(created.id()).text("status")).isEqualTo("EXPIRED");
        assertThat(reserved(productId)).isZero();
    }

    private void awaitReserved(long productId, int expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(reserved(productId)).isEqualTo(expected));
    }
}
