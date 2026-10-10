package com.example.order.order;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(long orderId) {
        super(ErrorCode.PAYMENT_DECLINED, "결제가 거절되었습니다: orderId=" + orderId);
    }
}
