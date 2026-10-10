package com.example.order.common.error;

public enum ErrorCode {
    VALIDATION_ERROR("Validation Failed"),
    PAYMENT_DECLINED("Payment Declined"),
    PRODUCT_NOT_FOUND("Product Not Found"),
    COUPON_NOT_FOUND("Coupon Not Found"),
    ORDER_NOT_FOUND("Order Not Found"),
    INSUFFICIENT_STOCK("Insufficient Stock"),
    COUPON_NOT_APPLICABLE("Coupon Not Applicable"),
    COUPON_EXHAUSTED("Coupon Exhausted"),
    DUPLICATE_COUPON_CODE("Duplicate Coupon Code"),
    INVALID_STATE("Invalid State"),
    IDEMPOTENCY_IN_PROGRESS("Idempotency Key In Progress"),
    IDEMPOTENCY_KEY_MISMATCH("Idempotency Key Mismatch"),
    PAYMENT_GATEWAY_UNAVAILABLE("Payment Gateway Unavailable");

    private final String title;

    ErrorCode(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
