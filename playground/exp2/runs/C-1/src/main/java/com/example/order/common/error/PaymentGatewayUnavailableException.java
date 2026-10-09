package com.example.order.common.error;

public class PaymentGatewayUnavailableException extends ApiException {

    public PaymentGatewayUnavailableException(String detail) {
        super(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, detail);
    }
}
