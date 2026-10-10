package com.example.order.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** PG payment record. paymentId lives only here (never in the order response). cardToken is never stored. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "payment_id", nullable = false, length = 100)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected Payment() {
    }

    public Payment(Long orderId, String paymentId, PaymentStatus status, long amount, Instant createdAt) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.status = status;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public void markRefunded(Instant now) {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = now;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }
}
