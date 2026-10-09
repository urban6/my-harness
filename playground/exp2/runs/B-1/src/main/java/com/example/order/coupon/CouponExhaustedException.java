package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class CouponExhaustedException extends BusinessException {

    public CouponExhaustedException(String code) {
        super(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다: code=" + code);
    }
}
