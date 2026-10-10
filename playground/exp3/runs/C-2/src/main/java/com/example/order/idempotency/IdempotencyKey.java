package com.example.order.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Read model of idempotency_keys; rows are written via native INSERT ... ON CONFLICT DO NOTHING. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    public static final String CREATE_ORDER = "CREATE_ORDER";
    public static final String PAY = "PAY";
    public static final String PAY_SCOPE = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation", nullable = false, length = 20)
    private String operation;

    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOutcome() {
        return outcome;
    }
}
