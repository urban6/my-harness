package com.example.order.order;

import com.example.order.common.Problems;
import com.example.order.common.Times;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Reads with lazy expiry: a due PENDING_PAYMENT order is really expired before it is shown. */
@Service
public class OrderQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);
    private static final int LAZY_SWEEP_LIMIT = 500;

    private final OrderRepository orders;
    private final OrderAssembler assembler;
    private final OrderTransitionService transitions;
    private final Clock clock;

    public OrderQueryService(OrderRepository orders, OrderAssembler assembler, OrderTransitionService transitions,
                             Clock clock) {
        this.orders = orders;
        this.assembler = assembler;
        this.transitions = transitions;
        this.clock = clock;
    }

    public OrderResponse get(long id) {
        Instant now = Times.now(clock);
        OrderRow row = orders.findById(id).orElseThrow(() -> Problems.orderNotFound(id));
        if (row.status() == OrderStatus.PENDING_PAYMENT && !row.expiresAt().isAfter(now)
                && transitions.expireIfDue(id)) {
            row = orders.findById(id).orElseThrow(() -> Problems.orderNotFound(id));
        }
        return assembler.assemble(row);
    }

    public OrderPage list(String userId, OrderStatus status, int size, Long beforeId) {
        Instant now = Times.now(clock);
        if (status == null || status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.EXPIRED) {
            for (Long dueId : orders.findDueIds(now, userId, LAZY_SWEEP_LIMIT)) {
                try {
                    transitions.expireIfDue(dueId);
                } catch (RuntimeException e) {
                    log.warn("Lazy expiry failed for order {}", dueId, e);
                }
            }
        }
        List<OrderRow> rows = orders.list(userId, status, beforeId, size + 1);
        boolean hasMore = rows.size() > size;
        List<OrderRow> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? Cursor.encode(page.get(page.size() - 1).id()) : null;
        return new OrderPage(assembler.assemble(page), next);
    }
}
