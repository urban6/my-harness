package com.example.order.common;

import com.example.order.order.OrderAction;
import com.example.order.order.OrderStatus;
import java.time.Instant;
import java.util.List;

/** Factory for every ApiException used by the services. */
public final class Problems {

    private Problems() {
    }

    public static ApiException validationFailed(List<FieldErrorDetail> errors) {
        return new ApiException(ProblemType.VALIDATION_FAILED, "Request validation failed").with("errors", errors);
    }

    public static ApiException validationFailed(String field, String message) {
        return validationFailed(List.of(new FieldErrorDetail(field, message)));
    }

    public static ApiException missingHeader(String header) {
        return new ApiException(ProblemType.MISSING_HEADER, "Required header '" + header + "' is missing")
                .with("header", header);
    }

    public static ApiException invalidCursor() {
        return new ApiException(ProblemType.INVALID_CURSOR, "The cursor is invalid");
    }

    public static ApiException productNotFound(long productId) {
        return new ApiException(ProblemType.PRODUCT_NOT_FOUND, "Product " + productId + " was not found")
                .with("productId", productId);
    }

    public static ApiException couponNotFound(String code) {
        return new ApiException(ProblemType.COUPON_NOT_FOUND, "Coupon '" + code + "' was not found")
                .with("couponCode", code);
    }

    public static ApiException orderNotFound(long orderId) {
        return new ApiException(ProblemType.ORDER_NOT_FOUND, "Order " + orderId + " was not found")
                .with("orderId", orderId);
    }

    public static ApiException couponCodeDuplicate(String code) {
        return new ApiException(ProblemType.COUPON_CODE_DUPLICATE, "Coupon code '" + code + "' already exists")
                .with("couponCode", code);
    }

    public static ApiException insufficientStock(long productId, int requested, int available) {
        return new ApiException(ProblemType.INSUFFICIENT_STOCK, "Not enough stock for product " + productId)
                .with("productId", productId).with("requested", requested).with("available", available);
    }

    public static ApiException invalidOrderState(OrderStatus current, OrderAction action) {
        return new ApiException(ProblemType.INVALID_ORDER_STATE,
                "Cannot " + action.wireName() + " an order in status " + current)
                .with("currentStatus", current.name()).with("action", action.wireName());
    }

    public static ApiException orderExpired(Instant expiresAt) {
        return new ApiException(ProblemType.ORDER_EXPIRED, "The order has expired")
                .with("expiresAt", expiresAt);
    }

    public static ApiException idempotencyKeyConflict(String detail) {
        return new ApiException(ProblemType.IDEMPOTENCY_KEY_CONFLICT, detail);
    }

    public static ApiException operationInProgress(String operation) {
        return new ApiException(ProblemType.OPERATION_IN_PROGRESS,
                "Another operation on this order is in progress; retry shortly")
                .with("operation", operation).header("Retry-After", "1");
    }

    public static ApiException couponNotInPeriod(String code) {
        return new ApiException(ProblemType.COUPON_NOT_IN_PERIOD, "Coupon '" + code + "' is not in its valid period")
                .with("couponCode", code);
    }

    public static ApiException couponMinOrderNotMet(String code, long minOrderAmount, long subtotal) {
        return new ApiException(ProblemType.COUPON_MIN_ORDER_NOT_MET,
                "Order subtotal is below the minimum order amount of coupon '" + code + "'")
                .with("couponCode", code).with("minOrderAmount", minOrderAmount).with("subtotal", subtotal);
    }

    public static ApiException couponExhausted(String code) {
        return new ApiException(ProblemType.COUPON_EXHAUSTED, "Coupon '" + code + "' has no remaining quantity")
                .with("couponCode", code);
    }

    public static ApiException gatewayError(long orderId, String detail) {
        return new ApiException(ProblemType.PG_GATEWAY_ERROR, detail)
                .with("orderId", orderId).with("retryable", true);
    }

    public static ApiException gatewayTimeout(long orderId, String detail) {
        return new ApiException(ProblemType.PG_GATEWAY_TIMEOUT, detail)
                .with("orderId", orderId).with("retryable", true);
    }
}
