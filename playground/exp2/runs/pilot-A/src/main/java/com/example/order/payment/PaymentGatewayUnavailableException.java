package com.example.order.payment;

/** PG 장애: 5xx 응답, 연결 실패, 제한 시간 초과, 해석할 수 없는 응답. */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
