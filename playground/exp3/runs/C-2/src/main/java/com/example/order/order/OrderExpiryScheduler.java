package com.example.order.order;

import com.example.order.common.TimeSupport;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled with order.expiry-sweep-enabled=false (tests call expireDueOrders directly). */
@Component
@ConditionalOnProperty(name = "order.expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    static final int BATCH = 100;

    private final OrderExpiryService expiryService;
    private final Clock clock;

    public OrderExpiryScheduler(OrderExpiryService expiryService, Clock clock) {
        this.expiryService = expiryService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval:PT10S}")
    public void sweep() {
        try {
            int expired;
            do {
                expired = expiryService.expireDueOrders(TimeSupport.now(clock), BATCH);
            } while (expired >= BATCH);
        } catch (RuntimeException e) {
            log.error("Order expiry sweep failed", e);
        }
    }
}
