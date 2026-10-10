package com.example.order.payment;

/** PG 호출 실패(타임아웃·5xx·비정상 응답). 결제 거절(DECLINED)은 예외가 아니라 정상 결과다. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
