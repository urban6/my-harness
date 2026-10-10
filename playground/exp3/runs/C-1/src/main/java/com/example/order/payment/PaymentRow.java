package com.example.order.payment;

public record PaymentRow(
        long id,
        long orderId,
        String idempotencyKey,
        String requestHash,
        long amount,
        PaymentStatus status,
        String pgPaymentId,
        int attemptCount) {
}
