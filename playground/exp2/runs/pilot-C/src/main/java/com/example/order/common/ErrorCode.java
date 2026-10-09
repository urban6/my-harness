package com.example.order.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "Payment declined"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "Coupon not found"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
    COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT, "Coupon not applicable"),
    COUPON_EXHAUSTED(HttpStatus.CONFLICT, "Coupon exhausted"),
    DUPLICATE_COUPON_CODE(HttpStatus.CONFLICT, "Duplicate coupon code"),
    INVALID_STATE(HttpStatus.CONFLICT, "Invalid order state"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotent request in progress"),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key mismatch"),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "https://example.com/problems/" + name().toLowerCase().replace('_', '-');
    }
}
