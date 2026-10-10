package com.example.order;

import java.time.Instant;

import com.example.order.coupon.Coupon;
import com.example.order.coupon.CouponType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponTest {

    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2025-12-31T00:00:00Z");

    private Coupon coupon(CouponType type, long value, Long min, Long max) {
        return new Coupon("C", type, value, min, max, 10, FROM, UNTIL);
    }

    @Test
    void discountFor_fixed_cappedBySubtotal() {
        assertThat(coupon(CouponType.FIXED, 3000, null, null).discountFor(10000)).isEqualTo(3000);
        assertThat(coupon(CouponType.FIXED, 3000, null, null).discountFor(2000)).isEqualTo(2000);
    }

    @Test
    void discountFor_rate_floorsAndHonorsMaxDiscount() {
        assertThat(coupon(CouponType.RATE, 15, null, null).discountFor(999)).isEqualTo(149);
        assertThat(coupon(CouponType.RATE, 50, null, 1000L).discountFor(10000)).isEqualTo(1000);
    }

    @Test
    void validity_isInclusiveAndChecksMinOrder() {
        Coupon c = coupon(CouponType.FIXED, 100, 5000L, null);
        assertThat(c.isValidAt(FROM)).isTrue();
        assertThat(c.isValidAt(UNTIL)).isTrue();
        assertThat(c.isValidAt(FROM.minusSeconds(1))).isFalse();
        assertThat(c.isValidAt(UNTIL.plusSeconds(1))).isFalse();
        assertThat(c.isSatisfiedBy(4999)).isFalse();
        assertThat(c.isSatisfiedBy(5000)).isTrue();
    }
}
