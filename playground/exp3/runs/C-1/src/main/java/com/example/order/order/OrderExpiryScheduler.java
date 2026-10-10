package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "order-payment.expiry", name = "sweep-enabled", havingValue = "true",
        matchIfMissing = true)
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderExpirySweeper sweeper;

    public OrderExpiryScheduler(OrderExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${order-payment.expiry.sweep-interval:PT10S}")
    public void sweep() {
        try {
            int expired = sweeper.sweepOnce();
            if (expired > 0) {
                log.info("Expired {} pending orders", expired);
            }
        } catch (RuntimeException e) {
            log.error("Order expiry sweep failed", e);
        }
    }
}
