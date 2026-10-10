package com.example.order.common;

import java.util.List;
import java.util.regex.Pattern;

public final class RequestHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("^[\\x21-\\x7E]+$");

    private RequestHeaders() {
    }

    public static String requireUserId(String value) {
        if (value == null || value.isBlank()) {
            throw Problems.missingHeader(USER_ID);
        }
        if (value.length() > 64) {
            throw Problems.validation(List.of(new FieldViolation(USER_ID, "length must be between 1 and 64")));
        }
        return value;
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw Problems.missingHeader(IDEMPOTENCY_KEY);
        }
        if (value.length() > 128 || !PRINTABLE_ASCII.matcher(value).matches()) {
            throw Problems.validation(List.of(new FieldViolation(IDEMPOTENCY_KEY,
                    "must be 1-128 printable ASCII characters without spaces")));
        }
        return value;
    }
}
