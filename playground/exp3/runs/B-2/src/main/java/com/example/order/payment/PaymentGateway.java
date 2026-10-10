package com.example.order.payment;

/** 외부 PG 포트. 실패(타임아웃·5xx·계약 위반)는 UPSTREAM DomainException으로 올린다. */
public interface PaymentGateway {

    ChargeResult charge(long orderId, long amount, String cardToken, String idempotencyKey);

    void refund(String paymentId);

    record ChargeResult(String paymentId, boolean approved) {}
}
