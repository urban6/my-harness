package com.example.order.web;

import com.example.order.web.error.ApiException;

/** 헤더 검증 (R3.2, R4.1): 누락·위반은 400 VALIDATION_ERROR. */
final class HeaderValidator {

    private HeaderValidator() {
    }

    static String userId(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation("X-User-Id: must not be blank");
        }
        if (value.length() > 50) {
            throw ApiException.validation("X-User-Id: size must be at most 50");
        }
        return value;
    }

    static String idempotencyKey(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.validation("Idempotency-Key: must be present");
        }
        if (value.length() > 64) {
            throw ApiException.validation("Idempotency-Key: size must be between 1 and 64");
        }
        return value;
    }
}
