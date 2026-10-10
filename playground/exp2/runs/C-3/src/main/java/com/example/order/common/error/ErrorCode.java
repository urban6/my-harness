package com.example.order.common.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(400),
    PAYMENT_DECLINED(402),
    PRODUCT_NOT_FOUND(404),
    COUPON_NOT_FOUND(404),
    ORDER_NOT_FOUND(404),
    INSUFFICIENT_STOCK(409),
    COUPON_NOT_APPLICABLE(409),
    COUPON_EXHAUSTED(409),
    DUPLICATE_COUPON_CODE(409),
    INVALID_STATE(409),
    IDEMPOTENCY_IN_PROGRESS(409),
    IDEMPOTENCY_KEY_MISMATCH(422),
    PAYMENT_GATEWAY_UNAVAILABLE(503),
    // 확장 (명세 밖 프레임워크 오류)
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    UNSUPPORTED_MEDIA_TYPE(415),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    public HttpStatus httpStatus() {
        return HttpStatus.valueOf(status);
    }

    /** VALIDATION_ERROR -> validation-error */
    public String typeSlug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_ERROR;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> INTERNAL_ERROR;
        };
    }
}
