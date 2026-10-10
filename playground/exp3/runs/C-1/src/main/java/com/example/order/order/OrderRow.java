package com.example.order.order;

import java.time.Instant;
import java.util.UUID;

public record OrderRow(
        long id,
        String userId,
        OrderStatus status,
        Long couponId,
        String couponCode,
        long subtotal,
        long discount,
        long totalPrice,
        String idempotencyKey,
        String requestHash,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        String leaseKind,
        UUID leaseToken,
        Instant leaseExpiresAt) {

    /** A lease is active when it has a token and has not yet expired (same predicate as the SQL guards). */
    public boolean leaseActive(Instant now) {
        return leaseToken != null && leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
    }
}
