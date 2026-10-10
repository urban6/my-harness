package com.example.order.payment;

public enum PaymentStatus {
    INITIATED, APPROVED, DECLINED, REFUNDED;

    public boolean isFinalDecision() {
        return this != INITIATED;
    }
}
