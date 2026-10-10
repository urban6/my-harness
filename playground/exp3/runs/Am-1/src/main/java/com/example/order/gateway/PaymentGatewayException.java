package com.example.order.gateway;

public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
