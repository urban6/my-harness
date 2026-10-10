package com.example.order;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R2.4 할인액 계산 (단위)")
class CouponDiscountTest {

    private static Coupon coupon(CouponType type, long value, Long max) {
        return new Coupon("C0DE", type, value, 0, max, 1, Instant.EPOCH, Instant.MAX);
    }

    @Test
    void fixed() {
        assertThat(coupon(CouponType.FIXED, 1500, null).discountFor(10_000)).isEqualTo(1500);
    }

    @Test
    void rateFloors() {
        assertThat(coupon(CouponType.RATE, 33, null).discountFor(1001)).isEqualTo(330); // 330.33
    }

    @Test
    void maxDiscountCapsBeforeSubtotal() {
        assertThat(coupon(CouponType.RATE, 100, 700L).discountFor(1000)).isEqualTo(700);
        assertThat(coupon(CouponType.FIXED, 5000, 9000L).discountFor(1000)).isEqualTo(1000);
    }

    @Test
    void handlesAmountsBeyondInt() {
        long subtotal = 20L * 1000 * 10_000_000; // 200,000,000,000
        assertThat(coupon(CouponType.RATE, 7, null).discountFor(subtotal)).isEqualTo(14_000_000_000L);
    }
}
