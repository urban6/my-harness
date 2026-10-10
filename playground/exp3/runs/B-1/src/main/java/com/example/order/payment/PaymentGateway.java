package com.example.order.payment;

public interface PaymentGateway {

    /** 같은 idempotencyKey 로 다시 호출해도 PG 에서 중복 결제되지 않는다. */
    PaymentResult charge(String idempotencyKey, long orderId, long amount, String cardToken);

    void refund(String paymentId);
}
