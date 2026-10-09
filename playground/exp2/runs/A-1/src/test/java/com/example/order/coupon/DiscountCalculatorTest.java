package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("R2.4 할인액 계산")
class DiscountCalculatorTest {

    @ParameterizedTest(name = "{0} value={1} max={2} subtotal={3} → {4}")
    @CsvSource(nullValues = "null", value = {
            "FIXED, 3000, null, 10000, 3000",
            "FIXED, 3000, 2000, 10000, 2000",   // maxDiscountAmount 상한
            "FIXED, 15000, null, 10000, 10000", // subtotal 상한
            "RATE, 10, null, 12345, 1234",      // floor(1234.5)
            "RATE, 33, null, 100, 33",
            "RATE, 33, null, 101, 33",          // floor(33.33)
            "RATE, 50, 3000, 10000, 3000",      // 정률 후 maxDiscountAmount 상한
            "RATE, 100, null, 10000, 10000",
            "RATE, 1, null, 99, 0",             // floor(0.99)
            "RATE, 10, null, 200000000000, 20000000000", // int 범위를 넘는 금액 (C1)
    })
    void discount(CouponType type, long value, Long max, long subtotal, long expected) {
        assertThat(DiscountCalculator.discount(type, value, max, subtotal)).isEqualTo(expected);
    }
}
