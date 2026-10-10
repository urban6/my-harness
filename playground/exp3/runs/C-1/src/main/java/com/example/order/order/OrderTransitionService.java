package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Problems;
import com.example.order.common.Times;
import com.example.order.product.StockRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional state transitions that need no PG call: expire, cancel(pending), ship, deliver.
 * Each transition is a conditional UPDATE (claim); side effects run only when the claim affected 1 row.
 */
@Service
public class OrderTransitionService {

    private final OrderRepository orders;
    private final OrderAssembler assembler;
    private final ReservationReleaser releaser;
    private final StockRepository stock;
    private final Clock clock;

    public OrderTransitionService(OrderRepository orders, OrderAssembler assembler, ReservationReleaser releaser,
                                  StockRepository stock, Clock clock) {
        this.orders = orders;
        this.assembler = assembler;
        this.releaser = releaser;
        this.stock = stock;
        this.clock = clock;
    }

    /** Expires one order if (and only if) it is due and not under an active lease. Safe to call concurrently. */
    @Transactional
    public boolean expireIfDue(long orderId) {
        Instant now = Times.now(clock);
        if (orders.claimExpire(orderId, now) != 1) {
            return false;
        }
        releaser.release(orderId, now);
        return true;
    }

    /** cancel on PENDING_PAYMENT. Keeps the lazy expiry commit when it then answers order-expired. */
    @Transactional(noRollbackFor = ApiException.class)
    public OrderResponse cancelPending(long orderId) {
        Instant now = Times.now(clock);
        OrderRow current = orders.findById(orderId).orElseThrow(() -> Problems.orderNotFound(orderId));
        if (current.status() != OrderStatus.PENDING_PAYMENT) {
            throw OrderStateMachine.failureFor(current, OrderAction.CANCEL, now);
        }
        if (!current.expiresAt().isAfter(now)) {
            expireIfDue(orderId);
            throw OrderStateMachine.failureFor(reload(orderId), OrderAction.CANCEL, now);
        }
        if (orders.claimCancel(orderId, now) != 1) {
            throw OrderStateMachine.failureFor(reload(orderId), OrderAction.CANCEL, now);
        }
        releaser.release(orderId, now);
        return assembler.load(orderId);
    }

    @Transactional
    public OrderResponse ship(long orderId) {
        Instant now = Times.now(clock);
        if (orders.claimShip(orderId, now) != 1) {
            throw OrderStateMachine.failureFor(reload(orderId), OrderAction.SHIP, now);
        }
        for (OrderItemRow item : releaser.sortedItems(orderId)) {
            if (!stock.commitShip(item.productId(), item.quantity(), now)) {
                throw new IllegalStateException("Stock invariant violated shipping product " + item.productId()
                        + " for order " + orderId);
            }
        }
        return assembler.load(orderId);
    }

    @Transactional
    public OrderResponse deliver(long orderId) {
        Instant now = Times.now(clock);
        if (orders.claimDeliver(orderId, now) != 1) {
            throw OrderStateMachine.failureFor(reload(orderId), OrderAction.DELIVER, now);
        }
        return assembler.load(orderId);
    }

    private OrderRow reload(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> Problems.orderNotFound(orderId));
    }
}
