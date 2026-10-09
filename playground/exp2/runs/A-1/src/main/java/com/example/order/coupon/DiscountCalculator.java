package com.example.order.coupon;

public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /**
     * FIXED는 value, RATE는 floor(subtotal × value / 100). 이어서 maxDiscountAmount, 마지막으로 subtotal로 상한.
     */
    public static long discount(CouponType type, long value, Long maxDiscountAmount, long subtotal) {
        long discount = switch (type) {
            case FIXED -> value;
            case RATE -> Math.floorDiv(Math.multiplyExact(subtotal, value), 100L);
        };
        if (maxDiscountAmount != null) {
            discount = Math.min(discount, maxDiscountAmount);
        }
        return Math.min(discount, subtotal);
    }
}
