package com.example.order.coupon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R2.4 할인액 계산 (단위)")
class CouponDiscountTest {

    private static Coupon coupon(CouponType type, long value, Long maxDiscount) {
        Instant now = Instant.now();
        return new Coupon("TEST1234", type, value, 0, maxDiscount, 10,
                now, now.toString(), now.plusSeconds(60), now.plusSeconds(60).toString(), now);
    }

    @Test
    void fixedDiscount() {
        assertThat(coupon(CouponType.FIXED, 3_000, null).discountFor(10_000)).isEqualTo(3_000);
    }

    @Test
    void rateDiscountIsFloored() {
        assertThat(coupon(CouponType.RATE, 15, null).discountFor(999)).isEqualTo(149);
        assertThat(coupon(CouponType.RATE, 1, null).discountFor(99)).isZero();
    }

    @Test
    void maxDiscountCapsBeforeSubtotal() {
        assertThat(coupon(CouponType.RATE, 50, 7_000L).discountFor(100_000)).isEqualTo(7_000);
        assertThat(coupon(CouponType.FIXED, 5_000, 4_000L).discountFor(3_000)).isEqualTo(3_000);
    }

    @Test
    void subtotalCapsLast() {
        assertThat(coupon(CouponType.FIXED, 5_000, null).discountFor(2_000)).isEqualTo(2_000);
        assertThat(coupon(CouponType.RATE, 100, null).discountFor(12_345)).isEqualTo(12_345);
    }

    @Test
    void largeSubtotalDoesNotOverflow() {
        long subtotal = 200_000_000_000L;
        assertThat(coupon(CouponType.RATE, 99, null).discountFor(subtotal)).isEqualTo(198_000_000_000L);
        assertThat(coupon(CouponType.FIXED, Long.MAX_VALUE, null).discountFor(subtotal)).isEqualTo(subtotal);
    }
}
