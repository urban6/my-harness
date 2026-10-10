package com.example.order.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 기한이 지난 결제 대기 주문을 EXPIRED로 바꾸고 예약·쿠폰 사용을 복원한다. */
@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private static final int BATCH = 100;
    private static final Duration GATEWAY_CALL_STALE_AFTER = Duration.ofSeconds(10);

    private final PurchaseOrderRepository orders;
    private final Reservations reservations;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderExpiryScheduler(PurchaseOrderRepository orders, Reservations reservations, TransactionTemplate tx,
                                Clock clock) {
        this.orders = orders;
        this.reservations = reservations;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 250)
    public void sweep() {
        try {
            List<Long> ids;
            do {
                Instant now = clock.instant();
                ids = orders.findExpiredIds(now, now.minus(GATEWAY_CALL_STALE_AFTER), PageRequest.of(0, BATCH));
                for (Long id : ids) {
                    expire(id);
                }
            } while (ids.size() == BATCH);
        } catch (RuntimeException e) {
            log.warn("Order expiry sweep failed", e);
        }
    }

    private void expire(Long id) {
        try {
            tx.executeWithoutResult(status -> orders.findByIdForUpdate(id).ifPresent(order -> {
                Instant now = clock.instant();
                if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isExpiredAt(now)
                        && !order.isGatewayCallInFlight(now)) {
                    order.changeStatus(OrderStatus.EXPIRED);
                    reservations.release(order);
                }
            }));
        } catch (RuntimeException e) {
            log.warn("Failed to expire order {}", id, e);
        }
    }
}
