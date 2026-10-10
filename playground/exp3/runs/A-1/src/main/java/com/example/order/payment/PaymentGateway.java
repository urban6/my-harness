package com.example.order.payment;

public interface PaymentGateway {

    /** 승인/거절은 결과로 돌려주고, 통신 오류·비정상 응답은 ApiException(502)으로 던진다. */
    PaymentResult charge(long orderId, long amount, String cardToken, String idempotencyKey);

    /** 환불이 확인되지 않으면 ApiException(502)을 던진다. */
    void refund(String paymentId);

    record PaymentResult(String paymentId, boolean approved) {
    }
}
