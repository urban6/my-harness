package com.example.order.coupon;

/** 할인액 계산(R2.4). 모든 피연산자가 비음수이므로 정수 나눗셈이 곧 floor. */
public final class CouponPolicy {

    private CouponPolicy() {
    }

    public static long discount(Coupon coupon, long subtotal) {
        long raw = coupon.getType() == CouponType.FIXED
                ? coupon.getValue()
                : Math.multiplyExact(subtotal, coupon.getValue()) / 100;
        long d = coupon.getMaxDiscountAmount() == null ? raw : Math.min(raw, coupon.getMaxDiscountAmount());
        return Math.min(d, subtotal);
    }
}
