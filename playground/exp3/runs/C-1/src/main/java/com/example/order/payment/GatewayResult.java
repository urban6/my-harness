package com.example.order.payment;

/** @param approved true for APPROVED, false for DECLINED (charge); always true for a REFUNDED refund. */
public record GatewayResult(String paymentId, boolean approved) {
}
