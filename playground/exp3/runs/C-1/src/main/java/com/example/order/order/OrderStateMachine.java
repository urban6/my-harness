package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Problems;
import java.time.Instant;

/**
 * Maps "a conditional UPDATE claimed 0 rows" to the 409 of 01_api_design.md section 3.3,
 * given the freshly re-read order.
 */
public final class OrderStateMachine {

    private OrderStateMachine() {
    }

    public static ApiException failureFor(OrderRow current, OrderAction action, Instant now) {
        boolean leaseActive = current.leaseActive(now);
        if (action == OrderAction.PAY || action == OrderAction.CANCEL) {
            if (current.status() == OrderStatus.EXPIRED) {
                return Problems.orderExpired(current.expiresAt());
            }
            if (current.status() == OrderStatus.PENDING_PAYMENT && !current.expiresAt().isAfter(now)) {
                return leaseActive
                        ? Problems.operationInProgress(current.leaseKind())
                        : Problems.orderExpired(current.expiresAt());
            }
        }
        if (leaseActive && action.canStartFrom(current.status())) {
            return Problems.operationInProgress(current.leaseKind());
        }
        return Problems.invalidOrderState(current.status(), action);
    }
}
