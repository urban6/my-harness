package com.example.order.order;

import com.example.order.common.TimeSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order expiry (D-08): scheduler sweep + lazy expiry. Each order is processed in its own transaction using
 * FOR UPDATE SKIP LOCKED so an in-flight payment is never touched.
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final PurchaseOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderInventory inventory;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderExpiryService(PurchaseOrderRepository orderRepository, OrderItemRepository itemRepository,
            OrderInventory inventory, Clock clock, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.inventory = inventory;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Expires up to {@code batch} due orders; returns how many were actually transitioned. */
    public int expireDueOrders(Instant now, int batch) {
        List<Long> ids = orderRepository.findDueIds(OrderStatus.PENDING_PAYMENT, now, PageRequest.of(0, batch));
        int expired = 0;
        for (Long id : ids) {
            try {
                if (expireOne(id, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to expire order {}", id, e);
            }
        }
        return expired;
    }

    /** Lazy expiry for one order in its own transaction; skipped if the row is locked or not due. */
    public boolean expireIfDue(long orderId) {
        return expireOne(orderId, TimeSupport.now(clock));
    }

    private boolean expireOne(long orderId, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status ->
                orderRepository.lockDueSkipLocked(orderId, now).map(order -> expireLocked(order, now)).orElse(false)));
    }

    /**
     * Expires an order the caller already holds a FOR UPDATE lock on, inside the caller's transaction.
     * Re-checks status and expiry under the lock. Returns true if the transition happened.
     */
    public boolean expireLockedIfDue(PurchaseOrder order, Instant now) {
        return order.isDue(now) && expireLocked(order, now);
    }

    private boolean expireLocked(PurchaseOrder order, Instant now) {
        if (!order.isDue(now)) {
            return false;
        }
        inventory.release(order, itemRepository.findByOrderIdOrderByIdAsc(order.getId()));
        order.transitionTo(OrderStatus.EXPIRED, now);
        return true;
    }
}
