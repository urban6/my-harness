package com.example.order.payment;

public interface PaymentGateway {

    enum ChargeStatus { APPROVED, DECLINED }

    record ChargeResult(String paymentId, ChargeStatus status) {
    }

    /** 승인/거절 응답을 반환한다. 통신 실패·비정상 응답은 {@link PaymentGatewayException}. */
    ChargeResult charge(long orderId, long amount, String cardToken, String idempotencyKey);

    void refund(String paymentId);
}
