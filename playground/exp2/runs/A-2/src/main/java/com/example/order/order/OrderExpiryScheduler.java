package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderService orderService;

    public OrderExpiryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.expiry-check-interval-ms}")
    public void expire() {
        try {
            int expired = orderService.expireDueOrders();
            if (expired > 0) {
                log.info("Expired {} unpaid orders", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Order expiry run failed", e);
        }
    }
}
