package com.example.order.payment;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class PaymentGatewayUnavailableException extends BusinessException {

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, message);
        initCause(cause);
    }
}
