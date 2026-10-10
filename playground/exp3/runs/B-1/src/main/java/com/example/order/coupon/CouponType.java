package com.example.order.coupon;

public enum CouponType {
    /** value 만큼 정액 할인 */
    FIXED,
    /** value(%) 만큼 정률 할인, maxDiscountAmount 로 상한 */
    RATE
}
