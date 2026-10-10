package com.example.order.common.error;

public class DuplicateCouponCodeException extends BusinessException {
    public DuplicateCouponCodeException(String detail) {
        super(ErrorCode.DUPLICATE_COUPON_CODE, detail);
    }
}
