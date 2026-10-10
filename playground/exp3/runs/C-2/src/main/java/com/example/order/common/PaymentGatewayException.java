package com.example.order.common;

import org.springframework.http.HttpStatus;

public class PaymentGatewayException extends ApiException {

    private PaymentGatewayException(HttpStatus status, String slug, String title, String detail) {
        super(status, slug, title, detail);
        with("retryable", true);
    }

    /** reason: UNAVAILABLE, SERVER_ERROR, REJECTED, BAD_RESPONSE. */
    public static PaymentGatewayException error(String reason) {
        return (PaymentGatewayException) new PaymentGatewayException(HttpStatus.BAD_GATEWAY, "payment-gateway-error",
                "Payment gateway error", "The payment gateway call failed (" + reason + "). The request can be retried.")
                .with("reason", reason);
    }

    public static PaymentGatewayException timeout() {
        return new PaymentGatewayException(HttpStatus.GATEWAY_TIMEOUT, "payment-gateway-timeout",
                "Payment gateway timeout", "The payment gateway did not respond in time. The request can be retried.");
    }
}
