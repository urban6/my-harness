package com.example.order.payment;

public interface PaymentGatewayClient {
    /** 승인/거절은 결과로, 장애는 PaymentGatewayUnavailableException 으로 알린다. */
    PgPaymentResult pay(String idempotencyKey, long orderId, long amount, String cardToken);

    void refund(String paymentId);
}
