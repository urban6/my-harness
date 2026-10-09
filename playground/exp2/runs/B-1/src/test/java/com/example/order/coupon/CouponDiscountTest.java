package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("R2.4 할인액 계산")
class CouponDiscountTest {

    private static Coupon coupon(CouponType type, long value, Long maxDiscount) {
        Instant now = Instant.now();
        return new Coupon("TEST0001", type, value, 0, maxDiscount, 10, now, now.plusSeconds(60), now);
    }

    @ParameterizedTest(name = "{0} value={1} max={2} subtotal={3} → {4}")
    @CsvSource(nullValues = "null", value = {
            "FIXED, 3000,  null, 10000, 3000",
            "FIXED, 3000,  2000, 10000, 2000",   // maxDiscountAmount 상한
            "FIXED, 15000, null, 10000, 10000",  // subtotal 상한
            "FIXED, 15000, 12000, 10000, 10000", // max 다음 subtotal 상한
            "RATE,  10,    null, 10000, 1000",
            "RATE,  15,    null, 9999,  1499",   // floor(1499.85)
            "RATE,  33,    null, 101,   33",     // floor(33.33)
            "RATE,  50,    3000, 10000, 3000",   // maxDiscountAmount 상한
            "RATE,  100,   null, 10000, 10000",
    })
    void calculateDiscount(CouponType type, long value, Long max, long subtotal, long expected) {
        assertThat(coupon(type, value, max).calculateDiscount(subtotal)).isEqualTo(expected);
    }

    @Test
    @DisplayName("C1 int 범위를 넘는 금액도 정확히 계산한다")
    void handlesAmountsBeyondInt() {
        long subtotal = 200_000_000_000L; // 10,000,000원 × 1,000개 × 20종
        assertThat(coupon(CouponType.RATE, 7, null).calculateDiscount(subtotal)).isEqualTo(14_000_000_000L);
    }
}
