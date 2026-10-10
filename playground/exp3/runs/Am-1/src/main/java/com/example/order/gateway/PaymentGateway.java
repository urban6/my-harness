package com.example.order.gateway;

public interface PaymentGateway {

    record PaymentResult(String paymentId, boolean approved) {
    }

    /** @throws PaymentGatewayException when the gateway gave no usable verdict (timeout, 5xx, bad body). */
    PaymentResult charge(String idempotencyKey, long orderId, long amount, String cardToken);

    /** @throws PaymentGatewayException when the refund was not confirmed. */
    void refund(String paymentId);
}
