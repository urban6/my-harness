package com.example.order.web.error;

import org.springframework.http.HttpStatus;

/** R11.3 의 code 표. */
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
    INVALID_STATE(HttpStatus.CONFLICT, "Invalid State"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotency In Progress"),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency Key Mismatch"),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment Gateway Unavailable");

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

    /** urn:problem-type:order-payment:{kebab-case code} */
    public String typeUri() {
        return "urn:problem-type:order-payment:" + name().toLowerCase().replace('_', '-');
    }
}
