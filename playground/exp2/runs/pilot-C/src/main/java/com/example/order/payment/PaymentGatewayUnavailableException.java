package com.example.order.payment;

public class PaymentGatewayUnavailableException extends RuntimeException {
    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
