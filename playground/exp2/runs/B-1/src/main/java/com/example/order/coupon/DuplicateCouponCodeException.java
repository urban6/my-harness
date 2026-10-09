package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class DuplicateCouponCodeException extends BusinessException {

    public DuplicateCouponCodeException(String code) {
        super(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다: code=" + code);
    }
}
