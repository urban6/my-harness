package com.example.order.coupon;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class CouponNotApplicableException extends BusinessException {

    public CouponNotApplicableException(String reason) {
        super(ErrorCode.COUPON_NOT_APPLICABLE, "쿠폰을 사용할 수 없습니다: " + reason);
    }
}
