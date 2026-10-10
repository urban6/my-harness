package com.example.order.coupon;

/** Integer-only discount rule (01_api_design.md section 3.2). */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    public static long calculate(CouponType type, long value, Long maxDiscountAmount, long subtotal) {
        long raw = type == CouponType.FIXED ? value : (subtotal * value) / 100; // RATE: floor
        if (maxDiscountAmount != null && maxDiscountAmount > 0) {
            raw = Math.min(raw, maxDiscountAmount);
        }
        return Math.min(raw, subtotal);
    }

    public static long calculate(Coupon coupon, long subtotal) {
        return calculate(coupon.getType(), coupon.getValue(), coupon.getMaxDiscountAmount(), subtotal);
    }
}
