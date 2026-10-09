package com.example.order.common.error;

public class PaymentDeclinedException extends ApiException {

    public PaymentDeclinedException(String detail) {
        super(ErrorCode.PAYMENT_DECLINED, detail);
    }
}
