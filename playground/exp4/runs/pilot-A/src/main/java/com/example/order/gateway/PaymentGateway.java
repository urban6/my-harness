package com.example.order.gateway;

/** 외부 PG 계약. 장애(5xx·연결 실패·2초 초과·해석 불가 응답)는 {@link GatewayUnavailableException}. */
public interface PaymentGateway {

    ChargeResult charge(String idempotencyKey, long orderId, long amount, String cardToken);

    void refund(String paymentId);

    record ChargeResult(boolean approved, String paymentId) {
    }

    class GatewayUnavailableException extends RuntimeException {
        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
