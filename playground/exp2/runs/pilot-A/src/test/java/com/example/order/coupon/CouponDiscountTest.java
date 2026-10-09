package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R2.4 할인액 계산")
class CouponDiscountTest {

    private static Coupon coupon(CouponType type, long value, Long maxDiscount) {
        return new Coupon("TEST", type, value, 0, maxDiscount, 10,
                OffsetDateTime.parse("2020-01-01T00:00:00Z"), OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                Instant.now());
    }

    @Test
    void fixed() {
        assertThat(coupon(CouponType.FIXED, 3000, null).discountFor(10_000)).isEqualTo(3000);
    }

    @Test
    void rateIsFloored() {
        assertThat(coupon(CouponType.RATE, 15, null).discountFor(999)).isEqualTo(149);
        assertThat(coupon(CouponType.RATE, 33, null).discountFor(10)).isEqualTo(3);
    }

    @Test
    void cappedByMaxDiscountThenSubtotal() {
        assertThat(coupon(CouponType.RATE, 50, 1000L).discountFor(10_000)).isEqualTo(1000);
        assertThat(coupon(CouponType.FIXED, 5000, 4000L).discountFor(3000)).isEqualTo(3000);
        assertThat(coupon(CouponType.RATE, 100, null).discountFor(12_345)).isEqualTo(12_345);
    }

    @Test
    void largeAmountsDoNotOverflow() {
        long subtotal = 200_000_000_000L; // 10,000,000원 × 1,000개 × 20종
        assertThat(coupon(CouponType.RATE, 99, null).discountFor(subtotal)).isEqualTo(198_000_000_000L);
    }

    @Test
    @DisplayName("R2.5 validFrom 이상 validUntil 미만에서만 유효")
    void validityWindow() {
        Coupon c = coupon(CouponType.FIXED, 1, null);
        assertThat(c.isValidAt(Instant.parse("2020-01-01T00:00:00Z"))).isTrue();
        assertThat(c.isValidAt(Instant.parse("2019-12-31T23:59:59Z"))).isFalse();
        assertThat(c.isValidAt(Instant.parse("2099-01-01T00:00:00Z"))).isFalse();
    }
}
