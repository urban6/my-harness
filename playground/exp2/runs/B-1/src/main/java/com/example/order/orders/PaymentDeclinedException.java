package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(Long orderId) {
        super(ErrorCode.PAYMENT_DECLINED, "결제가 거절되었습니다: orderId=" + orderId);
    }
}
