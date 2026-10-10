package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 결제 기한이 지난 주문을 주기적으로 만료시킨다. 만료는 expiresAt 후 2초 안에 반영되어야 한다(R6.2). */
@Component
public class OrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final OrderLifecycleService lifecycleService;

    public OrderExpirationScheduler(OrderLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${order.expiration-scan-interval-ms:500}")
    public void expireOverdueOrders() {
        for (Long orderId : lifecycleService.findExpiredPendingOrderIds(BATCH_SIZE)) {
            try {
                lifecycleService.expire(orderId);
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}", orderId, e);
            }
        }
    }
}
