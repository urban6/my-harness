package com.example.order.coupon;

public enum CouponType {
    /** 정액 할인: value = 할인 금액 */
    FIXED,
    /** 정률 할인: value = 퍼센트(1~100) */
    RATE
}
