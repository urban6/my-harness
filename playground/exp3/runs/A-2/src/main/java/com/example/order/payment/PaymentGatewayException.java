package com.example.order.payment;

/** PG와의 통신 실패, 타임아웃, 5xx, 해석할 수 없는 응답. 결제 결과가 확정되지 않았음을 뜻한다. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
