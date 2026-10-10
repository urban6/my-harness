package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscountCalculatorTest {

    @Test
    void fixedDiscountIsCappedBySubtotal() {
        assertThat(DiscountCalculator.discount(CouponType.FIXED, 5000, null, 30000)).isEqualTo(5000);
        assertThat(DiscountCalculator.discount(CouponType.FIXED, 50000, null, 30000)).isEqualTo(30000);
    }

    @Test
    void rateDiscountFloors() {
        assertThat(DiscountCalculator.discount(CouponType.RATE, 10, null, 1999)).isEqualTo(199);
        assertThat(DiscountCalculator.discount(CouponType.RATE, 100, null, 777)).isEqualTo(777);
    }

    @Test
    void maxDiscountCapsBeforeSubtotalCap() {
        assertThat(DiscountCalculator.discount(CouponType.RATE, 50, 1000L, 10000)).isEqualTo(1000);
        assertThat(DiscountCalculator.discount(CouponType.FIXED, 5000, 3000L, 2000)).isEqualTo(2000);
    }

    @Test
    void handlesAmountsBeyondIntRange() {
        long subtotal = 200_000_000_000L; // 20 items * 1000 qty * 10,000,000
        assertThat(DiscountCalculator.discount(CouponType.RATE, 99, null, subtotal)).isEqualTo(198_000_000_000L);
        assertThat(DiscountCalculator.discount(CouponType.FIXED, 5_000_000_000L, null, subtotal))
                .isEqualTo(5_000_000_000L);
    }
}
