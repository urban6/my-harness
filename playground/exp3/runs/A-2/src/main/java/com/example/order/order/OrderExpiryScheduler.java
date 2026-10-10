package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderService service;

    public OrderExpiryScheduler(OrderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval}")
    void sweep() {
        try {
            service.sweepDue();
        } catch (RuntimeException e) {
            log.warn("Order expiry sweep failed", e);
        }
    }
}
