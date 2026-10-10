package com.example.order.coupon;

/** R2.4 할인액 계산. 모든 계산은 long. */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    public static long discount(CouponType type, long value, Long maxDiscountAmount, long subtotal) {
        long discount = switch (type) {
            case FIXED -> value;
            case RATE -> Math.multiplyExact(subtotal, value) / 100; // 양수 → floor
        };
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }
}
