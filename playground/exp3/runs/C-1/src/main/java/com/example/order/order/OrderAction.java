package com.example.order.order;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Client-facing order operations and the statuses each can start from. */
public enum OrderAction {
    PAY(EnumSet.of(OrderStatus.PENDING_PAYMENT)),
    CANCEL(EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)),
    SHIP(EnumSet.of(OrderStatus.PAID)),
    DELIVER(EnumSet.of(OrderStatus.SHIPPED));

    private final Set<OrderStatus> sources;

    OrderAction(Set<OrderStatus> sources) {
        this.sources = sources;
    }

    public boolean canStartFrom(OrderStatus status) {
        return sources.contains(status);
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
