package com.example.order.payment;

/** PG 5xx, 연결 실패, 제한 시간 초과 등 PG 장애. */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
