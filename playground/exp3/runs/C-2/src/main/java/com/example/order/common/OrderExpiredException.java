package com.example.order.common;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/** Thrown by pay on an expired order; pay's transaction uses noRollbackFor so the EXPIRED transition commits. */
public class OrderExpiredException extends ApiException {

    public OrderExpiredException(long orderId, Instant expiresAt) {
        super(HttpStatus.CONFLICT, "order-expired", "Order expired",
                "Order " + orderId + " expired at " + expiresAt + ".");
        with("orderId", orderId).with("expiresAt", expiresAt.toString());
    }
}
