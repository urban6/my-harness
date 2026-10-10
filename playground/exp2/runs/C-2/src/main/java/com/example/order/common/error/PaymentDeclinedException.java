package com.example.order.common.error;

public class PaymentDeclinedException extends BusinessException {
    public PaymentDeclinedException(String detail) {
        super(ErrorCode.PAYMENT_DECLINED, detail);
    }
}
