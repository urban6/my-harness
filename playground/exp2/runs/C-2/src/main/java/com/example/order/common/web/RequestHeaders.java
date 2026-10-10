package com.example.order.common.web;

import com.example.order.common.error.RequestValidationException;

public final class RequestHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private RequestHeaders() {
    }

    public static String requireUserId(String value) {
        return require("header:" + USER_ID, value, 50);
    }

    public static String requireIdempotencyKey(String value) {
        return require("header:" + IDEMPOTENCY_KEY, value, 64);
    }

    private static String require(String field, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new RequestValidationException(field, "공백이 아닌 1~" + maxLength + "자여야 합니다");
        }
        return value;
    }
}
