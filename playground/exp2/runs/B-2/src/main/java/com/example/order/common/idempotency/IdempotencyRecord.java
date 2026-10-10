package com.example.order.common.idempotency;

record IdempotencyRecord(String fingerprint, boolean completed, Integer responseStatus,
                         String responseBody, String responseLocation) {
}
