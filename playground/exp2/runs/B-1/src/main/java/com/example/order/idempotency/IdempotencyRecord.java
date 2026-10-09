package com.example.order.idempotency;

public record IdempotencyRecord(
        String requestHash,
        boolean completed,
        Integer responseStatus,
        String responseBody,
        String responseLocation
) {
}
