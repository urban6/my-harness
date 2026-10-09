package com.example.order.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 시각이 지난 결제 대기 주문을 주기적으로 EXPIRED 로 바꾼다(R6.2: 만료 후 2초 안에 반영). */
@Component
class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final OrderLifecycleService lifecycleService;

    OrderExpiryScheduler(OrderLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval:PT0.5S}")
    void expireOverdueOrders() {
        for (Long orderId : lifecycleService.findExpiredOrderIds(BATCH_SIZE)) {
            try {
                lifecycleService.expire(orderId); // 주문마다 별도 트랜잭션
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}", orderId, e);
            }
        }
    }
}
