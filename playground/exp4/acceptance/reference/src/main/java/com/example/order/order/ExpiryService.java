package com.example.order.order;

import com.example.order.common.Times;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R6: PENDING_PAYMENT orders past expiresAt become EXPIRED. A sweeper runs every 500 ms, and reads call
 * {@link #expireDue()} first so the change is visible as soon as possible. Each order is expired in its own
 * transaction; rows locked by a concurrent payment/cancel are skipped (that path re-checks expiry itself).
 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final OrderRepository orders;
    private final ReservationReleaser releaser;
    private final TransactionTemplate tx;

    public ExpiryService(OrderRepository orders, ReservationReleaser releaser, PlatformTransactionManager tm) {
        this.orders = orders;
        this.releaser = releaser;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 500, initialDelay = 500)
    public void sweep() {
        try {
            expireDue();
        } catch (Exception e) {
            log.warn("expiry sweep failed", e);
        }
    }

    public void expireDue() {
        while (Boolean.TRUE.equals(tx.execute(s -> expireOne()))) {
            // keep going until nothing is due
        }
    }

    private boolean expireOne() {
        List<Order> due = orders.lockExpired(Times.now(), 1);
        if (due.isEmpty()) {
            return false;
        }
        expire(due.get(0));
        return true;
    }

    /** Caller must hold the order row lock and be inside a transaction. */
    public void expire(Order order) {
        releaser.releaseReservation(order);
        orders.updateStatus(order.id(), OrderStatus.EXPIRED);
    }
}
