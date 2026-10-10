package com.example.order.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Factories for the error catalogue in 01_api_design.md section 4. */
public final class Problems {

    private Problems() {
    }

    public static ApiException validation(List<FieldViolation> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed.").with("errors", errors);
    }

    public static ApiException missingHeader(String header) {
        return new ApiException(HttpStatus.BAD_REQUEST, "missing-required-header", "Missing required header",
                "Required header '" + header + "' is missing or blank.").with("header", header);
    }

    public static ApiException invalidParameter(String parameter, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid parameter", detail)
                .with("parameter", parameter);
    }

    public static ApiException productNotFound(long productId) {
        return new ApiException(HttpStatus.NOT_FOUND, "product-not-found", "Product not found",
                "Product " + productId + " was not found.").with("productId", productId);
    }

    public static ApiException couponNotFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, "coupon-not-found", "Coupon not found",
                "Coupon '" + code + "' was not found.").with("couponCode", code);
    }

    public static ApiException orderNotFound(long orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "Order not found",
                "Order " + orderId + " was not found.").with("orderId", orderId);
    }

    public static ApiException couponCodeDuplicated(String code) {
        return new ApiException(HttpStatus.CONFLICT, "coupon-code-duplicated", "Coupon code duplicated",
                "Coupon '" + code + "' already exists.").with("couponCode", code);
    }

    public static ApiException insufficientStock(long productId, int requested, int available) {
        return new ApiException(HttpStatus.CONFLICT, "insufficient-stock", "Insufficient stock",
                "Product " + productId + " has " + available + " available but " + requested + " requested.")
                .with("productId", productId).with("requested", requested).with("available", available);
    }

    /** reason: NOT_YET_VALID, EXPIRED, BELOW_MIN_ORDER_AMOUNT, EXHAUSTED. */
    public static ApiException couponNotApplicable(String code, String reason) {
        return new ApiException(HttpStatus.CONFLICT, "coupon-not-applicable", "Coupon not applicable",
                "Coupon '" + code + "' cannot be applied (" + reason + ").")
                .with("couponCode", code).with("reason", reason);
    }

    public static ApiException idempotencyKeyReused(String key) {
        return new ApiException(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency key reused",
                "The Idempotency-Key was already used with a different request.").with("idempotencyKey", key);
    }
}
