package com.example.order.common;

import org.springframework.http.HttpStatus;

/** Thrown after any lazy expiry transition; transactions list it in noRollbackFor so that transition commits. */
public class InvalidOrderStateException extends ApiException {

    public InvalidOrderStateException(long orderId, String currentStatus, String requestedAction) {
        super(HttpStatus.CONFLICT, "invalid-order-state", "Invalid order state",
                "Order " + orderId + " is " + currentStatus + " and cannot be processed for '" + requestedAction + "'.");
        with("orderId", orderId).with("currentStatus", currentStatus).with("requestedAction", requestedAction);
    }
}
