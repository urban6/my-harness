package com.example.order.common;

import org.springframework.http.HttpStatus;

/** slug <-> status mapping of 01_api_design.md section 1.1. */
public enum ProblemType {
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Validation failed"),
    MISSING_HEADER("missing-header", HttpStatus.BAD_REQUEST, "Required header is missing"),
    MALFORMED_REQUEST("malformed-request", HttpStatus.BAD_REQUEST, "Malformed request"),
    INVALID_CURSOR("invalid-cursor", HttpStatus.BAD_REQUEST, "Invalid cursor"),
    PRODUCT_NOT_FOUND("product-not-found", HttpStatus.NOT_FOUND, "Product not found"),
    COUPON_NOT_FOUND("coupon-not-found", HttpStatus.NOT_FOUND, "Coupon not found"),
    ORDER_NOT_FOUND("order-not-found", HttpStatus.NOT_FOUND, "Order not found"),
    RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    COUPON_CODE_DUPLICATE("coupon-code-duplicate", HttpStatus.CONFLICT, "Coupon code already exists"),
    INSUFFICIENT_STOCK("insufficient-stock", HttpStatus.CONFLICT, "Insufficient stock"),
    INVALID_ORDER_STATE("invalid-order-state", HttpStatus.CONFLICT, "Invalid order state"),
    ORDER_EXPIRED("order-expired", HttpStatus.CONFLICT, "Order expired"),
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", HttpStatus.CONFLICT, "Idempotency key conflict"),
    OPERATION_IN_PROGRESS("operation-in-progress", HttpStatus.CONFLICT, "Operation in progress"),
    COUPON_NOT_IN_PERIOD("coupon-not-in-period", HttpStatus.UNPROCESSABLE_ENTITY, "Coupon is not in its valid period"),
    COUPON_MIN_ORDER_NOT_MET("coupon-min-order-not-met", HttpStatus.UNPROCESSABLE_ENTITY, "Minimum order amount not met"),
    COUPON_EXHAUSTED("coupon-exhausted", HttpStatus.UNPROCESSABLE_ENTITY, "Coupon exhausted"),
    PG_GATEWAY_ERROR("pg-gateway-error", HttpStatus.BAD_GATEWAY, "Payment gateway error"),
    PG_GATEWAY_TIMEOUT("pg-gateway-timeout", HttpStatus.GATEWAY_TIMEOUT, "Payment gateway timeout"),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    public static final String URN_PREFIX = "urn:problem:order-payment:";

    private final String slug;
    private final HttpStatus status;
    private final String title;

    ProblemType(String slug, HttpStatus status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public String slug() {
        return slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return URN_PREFIX + slug;
    }
}
