package com.example.order.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The single place that defines which status transitions exist (R15). */
public enum OrderStatus {
    PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED, SHIPPED, DELIVERED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(PENDING_PAYMENT, EnumSet.of(PAID, PAYMENT_FAILED, EXPIRED, CANCELLED));
        ALLOWED.put(PAID, EnumSet.of(SHIPPED, REFUNDED));
        ALLOWED.put(SHIPPED, EnumSet.of(DELIVERED));
        for (OrderStatus s : values()) {
            ALLOWED.putIfAbsent(s, EnumSet.noneOf(OrderStatus.class));
        }
    }

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Guard used by every conditional-UPDATE method before issuing its SQL (programming-error check). */
    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Illegal order transition " + from + " -> " + to);
        }
    }
}
