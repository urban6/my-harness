package com.example.order.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String idemKey;
    private String fingerprint;
    private Long orderId;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String userId, String idemKey, String fingerprint) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.fingerprint = fingerprint;
    }

    public String getFingerprint() { return fingerprint; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
