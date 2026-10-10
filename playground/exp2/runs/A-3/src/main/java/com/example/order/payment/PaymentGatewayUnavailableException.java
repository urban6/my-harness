package com.example.order.payment;

/** PG 5xx, 연결 실패, 시간 초과 (R5.6, R7.3). */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
