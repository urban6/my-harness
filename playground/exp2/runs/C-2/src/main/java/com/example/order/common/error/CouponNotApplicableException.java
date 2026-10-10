package com.example.order.common.error;

public class CouponNotApplicableException extends BusinessException {
    public CouponNotApplicableException(String detail) {
        super(ErrorCode.COUPON_NOT_APPLICABLE, detail);
    }
}
