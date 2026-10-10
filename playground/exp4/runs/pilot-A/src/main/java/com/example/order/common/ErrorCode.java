package com.example.order.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT),
    COUPON_EXHAUSTED(HttpStatus.CONFLICT),
    DUPLICATE_COUPON_CODE(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
