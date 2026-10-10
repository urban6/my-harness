package com.example.order.common.error;

public class CouponNotFoundException extends BusinessException {
    public CouponNotFoundException(String detail) {
        super(ErrorCode.COUPON_NOT_FOUND, detail);
    }
}
