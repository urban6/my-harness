package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.coupon.CouponType;
import com.example.order.coupon.DiscountCalculator;
import com.example.order.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class CouponDiscountSmokeTest extends AbstractIntegrationTest {

    @Test
    void calculator_fixedRateFloorMaxCapAndSubtotalCap() {
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 3000, null, 10000)).isEqualTo(3000);
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 30000, null, 10000)).isEqualTo(10000);
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 15, null, 999)).isEqualTo(149); // floor(149.85)
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 50, 2000L, 10000)).isEqualTo(2000);
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 5000, 1000L, 10000)).isEqualTo(1000);
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 50, 0L, 10000)).isEqualTo(5000); // 0 = no cap
    }

    @Test
    void createOrder_withRateCoupon_appliesDiscountAndIncrementsUsedCount() throws Exception {
        long product = api.createProduct("Monitor", 10000, 10);
        api.createCoupon("TEN", "RATE", 10, 1000L, null, 5);

        api.postOrder("user-1", "key-1", api.orderBody("TEN", product, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponCode").value("TEN"))
                .andExpect(jsonPath("$.subtotal").value(30000))
                .andExpect(jsonPath("$.discount").value(3000))
                .andExpect(jsonPath("$.totalPrice").value(27000));
        assertThat(couponUsed("TEN")).isEqualTo(1);
    }

    @Test
    void createOrder_couponRuleViolations_return422AndLeaveNoSideEffects() throws Exception {
        long product = api.createProduct("Monitor", 10000, 10);
        api.createCoupon("BIGMIN", "FIXED", 500, 50000L, null, 5);
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("PAST", "FIXED", 500, null, null, 5, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)))).andReturn();

        api.postOrder("u", "k1", api.orderBody("BIGMIN", product, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-min-order-not-met"))
                .andExpect(jsonPath("$.minOrderAmount").value(50000))
                .andExpect(jsonPath("$.subtotal").value(10000));
        api.postOrder("u", "k2", api.orderBody("PAST", product, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-in-period"));
        api.postOrder("u", "k3", api.orderBody("MISSING", product, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-found"));

        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("BIGMIN")).isZero();
    }

    @Test
    void createOrder_exhaustedCoupon_returns422() throws Exception {
        long product = api.createProduct("Monitor", 10000, 10);
        api.createCoupon("ONCE", "FIXED", 1000, null, null, 1);
        api.createOrder("u1", "k1", "ONCE", product, 1);

        api.postOrder("u2", "k2", api.orderBody("ONCE", product, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-exhausted"));
        assertThat(couponUsed("ONCE")).isEqualTo(1);
        assertThat(reserved(product)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- R06 additions

    @Test
    @DisplayName("R06 calculator boundaries: RATE 100 = subtotal, zero subtotal, floor on every remainder, no overflow at the documented maximum")
    void calculator_boundaries() {
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 100, null, 12345)).isEqualTo(12345);
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 1, null, 99)).isZero(); // floor(0.99)
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 1, null, 100)).isEqualTo(1);
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 500, null, 0)).isZero();
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 33, null, 100)).isEqualTo(33);
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 33, null, 101)).isEqualTo(33); // floor(33.33)
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 1000, 1000L, 1000)).isEqualTo(1000);
        assertThat(DiscountCalculator.calculate(CouponType.FIXED, 1000, 999L, 5000)).isEqualTo(999);
        // 100 items * 10_000 units * 1e9 price = 1e15 subtotal; times 100 must not overflow
        assertThat(DiscountCalculator.calculate(CouponType.RATE, 100, null, 1_000_000_000_000_000L)).isEqualTo(1_000_000_000_000_000L);
    }

    @Test
    @DisplayName("R06 FIXED discount larger than the subtotal is capped at the subtotal (totalPrice 0, never negative)")
    void createOrder_fixedDiscountCannotExceedSubtotal() throws Exception {
        long product = api.createProduct("Pen", 1000, 10);
        api.createCoupon("HUGE", "FIXED", 50_000, null, null, 5);

        api.postOrder("u", "k", api.orderBody("HUGE", product, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(3000))
                .andExpect(jsonPath("$.discount").value(3000))
                .andExpect(jsonPath("$.totalPrice").value(0));
    }

    @Test
    @DisplayName("R06 RATE discount is floored, RATE 100 makes the order free, maxDiscountAmount caps FIXED and RATE")
    void createOrder_rateFloorAndCaps() throws Exception {
        long product = api.createProduct("Pen", 333, 100);
        api.createCoupon("R15", "RATE", 15, null, null, 5);
        api.createCoupon("R100", "RATE", 100, null, null, 5);
        api.createCoupon("R50CAP", "RATE", 50, null, 200L, 5);
        api.createCoupon("F500CAP", "FIXED", 500, null, 120L, 5);

        api.postOrder("u", "k1", api.orderBody("R15", product, 3)) // 999 * 15 / 100 = 149.85 -> 149
                .andExpect(jsonPath("$.discount").value(149)).andExpect(jsonPath("$.totalPrice").value(850));
        api.postOrder("u", "k2", api.orderBody("R100", product, 3))
                .andExpect(jsonPath("$.discount").value(999)).andExpect(jsonPath("$.totalPrice").value(0));
        api.postOrder("u", "k3", api.orderBody("R50CAP", product, 3))
                .andExpect(jsonPath("$.discount").value(200)).andExpect(jsonPath("$.totalPrice").value(799));
        api.postOrder("u", "k4", api.orderBody("F500CAP", product, 3))
                .andExpect(jsonPath("$.discount").value(120)).andExpect(jsonPath("$.totalPrice").value(879));
    }

    @Test
    @DisplayName("R06 validity window is inclusive on both ends: valid at validFrom and validUntil, 422 one microsecond outside")
    void createOrder_validityWindowBoundaries() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant until = Instant.parse("2030-01-01T01:00:00Z");
        api.postCoupon(api.couponBody("WINDOW", "FIXED", 100, null, null, 10, from, until)).andExpect(status().isCreated());

        clock.set(from.minus(Duration.ofNanos(1000)));
        api.postOrder("u", "k1", api.orderBody("WINDOW", product, 1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-in-period"));
        clock.set(from);
        api.postOrder("u", "k2", api.orderBody("WINDOW", product, 1)).andExpect(status().isCreated());
        clock.set(until);
        api.postOrder("u", "k3", api.orderBody("WINDOW", product, 1)).andExpect(status().isCreated());
        clock.set(until.plus(Duration.ofNanos(1000)));
        api.postOrder("u", "k4", api.orderBody("WINDOW", product, 1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-in-period"));

        assertThat(couponUsed("WINDOW")).isEqualTo(2);
    }

    @Test
    @DisplayName("R06 a coupon that is not valid yet is 422 coupon-not-in-period")
    void createOrder_couponNotYetValid_returns422() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("FUTURE", "FIXED", 100, null, null, 10, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))));

        api.postOrder("u", "k", api.orderBody("FUTURE", product, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-in-period"))
                .andExpect(jsonPath("$.couponCode").value("FUTURE"));
        assertThat(couponUsed("FUTURE")).isZero();
    }

    @Test
    @DisplayName("R06 minOrderAmount is inclusive: subtotal == min is accepted, min - 1 is 422 with the amounts in the problem")
    void createOrder_minOrderAmountBoundary() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        api.createCoupon("MIN5K", "FIXED", 100, 5000L, null, 10);

        api.postOrder("u", "k1", api.orderBody("MIN5K", product, 5)).andExpect(status().isCreated());
        api.postOrder("u", "k2", api.orderBody("MIN5K", product, 4))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-min-order-not-met"))
                .andExpect(jsonPath("$.minOrderAmount").value(5000))
                .andExpect(jsonPath("$.subtotal").value(4000));
        assertThat(couponUsed("MIN5K")).isEqualTo(1);
        assertThat(reserved(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("R06 rule order: period is checked before minimum amount, minimum amount before exhaustion")
    void createOrder_couponRuleOrder() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("EXPIRED-MIN", "FIXED", 100, 1_000_000L, null, 1, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1))));
        api.createCoupon("MIN-EXH", "FIXED", 100, 1_000_000L, null, 1);
        jdbc.update("UPDATE coupons SET used_count = total_quantity WHERE code = 'MIN-EXH'");

        api.postOrder("u", "k1", api.orderBody("EXPIRED-MIN", product, 1))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-in-period"));
        api.postOrder("u", "k2", api.orderBody("MIN-EXH", product, 1))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-min-order-not-met"));
    }

    @Test
    @DisplayName("R06 a user may use the same coupon on several orders (only the total quantity is limited)")
    void createOrder_sameUserMayReuseCoupon() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        api.createCoupon("MANY", "FIXED", 100, null, null, 3);

        api.createOrder("u", "k1", "MANY", product, 1);
        api.createOrder("u", "k2", "MANY", product, 1);
        api.createOrder("u", "k3", "MANY", product, 1);

        assertThat(couponUsed("MANY")).isEqualTo(3);
        api.postOrder("u", "k4", api.orderBody("MANY", product, 1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-exhausted"));
    }

    @Test
    @DisplayName("R06 the used quantity is returned on cancel, expiry, payment decline and refund - and can be used again")
    void couponQuantity_isReturnedByEveryReleasePath() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        api.createCoupon("ONE", "FIXED", 100, null, null, 1);

        long cancelled = api.createOrder("u1", "k1", "ONE", product, 1).get("id").asLong();
        api.postOrder("u2", "x", api.orderBody("ONE", product, 1)).andExpect(status().isUnprocessableEntity());
        api.action(cancelled, "cancel").andExpect(status().isOk());
        assertThat(couponUsed("ONE")).isZero();

        long declined = api.createOrder("u1", "k2", "ONE", product, 1).get("id").asLong();
        GATEWAY.chargeBehavior(com.example.order.support.PaymentGatewayStub.Behavior.DECLINE);
        api.pay(declined, "pay-d", "tok").andExpect(jsonPath("$.status").value("PAYMENT_FAILED"));
        assertThat(couponUsed("ONE")).isZero();
        GATEWAY.chargeBehavior(com.example.order.support.PaymentGatewayStub.Behavior.OK);

        long expired = api.createOrder("u1", "k3", "ONE", product, 1).get("id").asLong();
        clock.advance(Duration.ofMinutes(16));
        api.getOrder(expired).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(couponUsed("ONE")).isZero();

        long refunded = api.createPaidOrder("u1", "k4", "ONE", product, 1);
        assertThat(couponUsed("ONE")).isEqualTo(1);
        api.action(refunded, "cancel").andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(couponUsed("ONE")).isZero();
    }

    @Test
    @DisplayName("R06 the coupon period is only checked when the order is created, not again at payment time")
    void coupon_periodIsNotRevalidatedAtPayment() throws Exception {
        long product = api.createProduct("Pen", 1000, 100);
        Instant start = clock.instant();
        api.postCoupon(api.couponBody("SHORT", "FIXED", 100, null, null, 5, start.minusSeconds(60), start.plus(Duration.ofMinutes(5))));
        long orderId = api.createOrder("u", "k", "SHORT", product, 1).get("id").asLong();

        clock.advance(Duration.ofMinutes(10)); // coupon window closed, order TTL (15 min) still open
        api.pay(orderId, "pay-1", "tok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(GATEWAY.charges()).hasSize(1);
    }

    @Test
    @DisplayName("R06/R05 amounts beyond the int range are handled as long: 10_000 x 1_000_000_000 with a 50% RATE coupon")
    void createOrder_largeAmounts_doNotOverflow() throws Exception {
        long product = api.createProduct("Gold", 1_000_000_000L, 10_000);
        api.createCoupon("HALF", "RATE", 50, null, null, 2);

        api.postOrder("u", "k", api.orderBody("HALF", product, 10_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(10_000_000_000_000L))
                .andExpect(jsonPath("$.discount").value(5_000_000_000_000L))
                .andExpect(jsonPath("$.totalPrice").value(5_000_000_000_000L));
    }
}
