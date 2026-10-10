package com.example.order.payment;

public record PaymentResult(boolean approved, String paymentId) {

    public static PaymentResult approved(String paymentId) {
        return new PaymentResult(true, paymentId);
    }

    public static PaymentResult declined(String paymentId) {
        return new PaymentResult(false, paymentId);
    }
}
