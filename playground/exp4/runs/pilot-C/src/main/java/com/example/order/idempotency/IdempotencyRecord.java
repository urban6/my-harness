package com.example.order.idempotency;

public record IdempotencyRecord(
        long id,
        String userId,
        String requestPath,
        String bodyHash,
        String status,
        Integer responseStatus,
        String responseBody,
        String responseLocation) {

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
