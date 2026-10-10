package com.example.order.common.error;

public class CouponExhaustedException extends BusinessException {
    public CouponExhaustedException(String detail) {
        super(ErrorCode.COUPON_EXHAUSTED, detail);
    }
}
