package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** expiresAt이 지난 결제 대기 주문을 EXPIRED로 바꾼다 (R6). 주기가 짧아 만료 후 2초 안에 반영된다. */
@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);
    private static final int BATCH_SIZE = 100;

    private final OrderService orderService;

    public OrderExpiryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.expiry-poll-interval-ms:250}")
    public void expireDueOrders() {
        try {
            for (Long id : orderService.findExpiredPendingIds(BATCH_SIZE)) {
                orderService.expireIfDue(id);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to expire pending orders", e);
        }
    }
}
