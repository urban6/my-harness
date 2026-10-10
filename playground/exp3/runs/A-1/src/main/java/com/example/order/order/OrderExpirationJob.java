package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationJob.class);

    private final OrderService service;

    public OrderExpirationJob(OrderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${order.expiration-sweep-interval:PT1S}")
    void sweep() {
        try {
            service.expireDueOrders();
        } catch (RuntimeException e) {
            log.warn("order expiration sweep failed", e);
        }
    }
}
