package com.example.order.payment;

public record PgPaymentResult(String paymentId, PgPaymentStatus status) {
}
