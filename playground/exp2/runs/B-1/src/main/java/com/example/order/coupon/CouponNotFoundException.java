package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class CouponNotFoundException extends BusinessException {

    public CouponNotFoundException(String code) {
        super(ErrorCode.COUPON_NOT_FOUND, "쿠폰을 찾을 수 없습니다: code=" + code);
    }
}
