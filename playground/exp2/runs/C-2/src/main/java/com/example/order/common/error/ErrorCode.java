package com.example.order.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation Error"),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "Payment Declined"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product Not Found"),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "Coupon Not Found"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order Not Found"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient Stock"),
    COUPON_NOT_APPLICABLE(HttpStatus.CONFLICT, "Coupon Not Applicable"),
    COUPON_EXHAUSTED(HttpStatus.CONFLICT, "Coupon Exhausted"),
    DUPLICATE_COUPON_CODE(HttpStatus.CONFLICT, "Duplicate Coupon Code"),
    INVALID_STATE(HttpStatus.CONFLICT, "Invalid Order State"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotent Request In Progress"),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency Key Mismatch"),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment Gateway Unavailable"),
    // 계약 밖 오류 (01 설계 11.2)
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource Not Found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");

    private static final String TYPE_BASE = "https://order-service.example.com/problems/";

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
        return TYPE_BASE + name().toLowerCase().replace('_', '-');
    }
}
