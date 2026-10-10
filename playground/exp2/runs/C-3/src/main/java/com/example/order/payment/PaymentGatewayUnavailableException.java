package com.example.order.payment;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

/** PG 5xx, 연결 실패, 타임아웃, 계약 밖 응답 -> 503 PAYMENT_GATEWAY_UNAVAILABLE */
public class PaymentGatewayUnavailableException extends BusinessException {

    public PaymentGatewayUnavailableException(String detail, Throwable cause) {
        super(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, detail);
        if (cause != null) {
            initCause(cause);
        }
    }
}
