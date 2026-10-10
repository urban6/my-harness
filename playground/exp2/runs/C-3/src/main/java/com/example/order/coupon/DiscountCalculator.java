package com.example.order.coupon;

public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /** R2.4: FIXED=value, RATE=floor(subtotal*value/100) -> maxDiscountAmount 상한 -> subtotal 상한 */
    public static long discount(Coupon coupon, long subtotal) {
        long d = coupon.getType() == CouponType.FIXED
                ? coupon.getValue()
                : Math.multiplyExact(subtotal, coupon.getValue()) / 100;
        if (coupon.getMaxDiscountAmount() != null) {
            d = Math.min(d, coupon.getMaxDiscountAmount());
        }
        return Math.min(d, subtotal);
    }
}
