package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R2.4·R2.5 쿠폰 도메인 규칙")
class CouponTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    private static Coupon coupon(CouponType type, long value, Long maxDiscount) {
        return new Coupon("TEST0001", type, value, 0, maxDiscount, 10,
                NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("FIXED는 value만큼, subtotal을 넘지 않는다")
    void fixed() {
        assertThat(coupon(CouponType.FIXED, 3000, null).discountFor(10_000)).isEqualTo(3000);
        assertThat(coupon(CouponType.FIXED, 3000, null).discountFor(2_000)).isEqualTo(2000);
    }

    @Test
    @DisplayName("RATE는 floor(subtotal × value / 100)")
    void rate() {
        assertThat(coupon(CouponType.RATE, 15, null).discountFor(9_999)).isEqualTo(1_499);
        assertThat(coupon(CouponType.RATE, 100, null).discountFor(9_999)).isEqualTo(9_999);
    }

    @Test
    @DisplayName("maxDiscountAmount로 상한, 그다음 subtotal로 상한")
    void caps() {
        assertThat(coupon(CouponType.RATE, 50, 1_000L).discountFor(10_000)).isEqualTo(1_000);
        assertThat(coupon(CouponType.FIXED, 5_000, 4_000L).discountFor(3_000)).isEqualTo(3_000);
    }

    @Test
    @DisplayName("int 범위를 넘는 금액도 정확히 계산한다(C1)")
    void largeAmounts() {
        long subtotal = 200_000_000_000L;
        assertThat(coupon(CouponType.RATE, 33, null).discountFor(subtotal)).isEqualTo(66_000_000_000L);
    }

    @Test
    @DisplayName("유효 기간은 validFrom 이상 validUntil 미만")
    void validityWindow() {
        Coupon coupon = new Coupon("TEST0001", CouponType.FIXED, 1, 0, null, 1, NOW, NOW.plusSeconds(10));
        assertThat(coupon.isValidAt(NOW.minusNanos(1000))).isFalse();
        assertThat(coupon.isValidAt(NOW)).isTrue();
        assertThat(coupon.isValidAt(NOW.plusSeconds(10).minusNanos(1000))).isTrue();
        assertThat(coupon.isValidAt(NOW.plusSeconds(10))).isFalse();
    }
}
