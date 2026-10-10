package com.example.order.payment;

import com.example.order.common.ApiException;
import com.example.order.common.Problems;

/** PG call failure; never carries card data. */
public class GatewayException extends RuntimeException {

    public enum Kind { TIMEOUT, ERROR }

    private final Kind kind;

    public GatewayException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public ApiException toApiException(long orderId) {
        return kind == Kind.TIMEOUT
                ? Problems.gatewayTimeout(orderId, "The payment gateway did not respond in time; retry with the same Idempotency-Key")
                : Problems.gatewayError(orderId, "The payment gateway failed or returned an unexpected response; retry with the same Idempotency-Key");
    }
}
