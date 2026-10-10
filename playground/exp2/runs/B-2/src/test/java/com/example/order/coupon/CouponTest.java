package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** R2.4 할인액 계산, R2.5 유효 기간 경계 */
class CouponTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-02-01T00:00:00Z");

    private static Coupon coupon(CouponType type, long value, Long maxDiscount) {
        return new Coupon("TEST1234", type, value, 0, maxDiscount, 10, FROM, UNTIL);
    }

    @Test
    void fixedDiscount() {
        assertThat(coupon(CouponType.FIXED, 3_000, null).discountFor(10_000)).isEqualTo(3_000);
    }

    @Test
    void rateDiscountIsFloored() {
        assertThat(coupon(CouponType.RATE, 15, null).discountFor(9_999)).isEqualTo(1_499);
        assertThat(coupon(CouponType.RATE, 1, null).discountFor(99)).isZero();
    }

    @Test
    void maxDiscountCapAppliesBeforeSubtotalCap() {
        assertThat(coupon(CouponType.RATE, 50, 7_000L).discountFor(100_000)).isEqualTo(7_000);
        assertThat(coupon(CouponType.FIXED, 10_000, 7_000L).discountFor(5_000)).isEqualTo(5_000);
    }

    @Test
    void discountNeverExceedsSubtotal() {
        assertThat(coupon(CouponType.FIXED, 50_000, null).discountFor(4_000)).isEqualTo(4_000);
        assertThat(coupon(CouponType.RATE, 100, null).discountFor(4_000)).isEqualTo(4_000);
    }

    @Test
    void validityIsHalfOpenInterval() {
        Coupon coupon = coupon(CouponType.FIXED, 1, null);

        assertThat(coupon.isValidAt(FROM.minusNanos(1000))).isFalse();
        assertThat(coupon.isValidAt(FROM)).isTrue();
        assertThat(coupon.isValidAt(UNTIL.minusNanos(1000))).isTrue();
        assertThat(coupon.isValidAt(UNTIL)).isFalse();
    }
}
