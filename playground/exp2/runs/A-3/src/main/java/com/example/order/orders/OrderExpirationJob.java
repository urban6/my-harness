package com.example.order.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** R6. expiresAt 이 지난 결제 대기 주문을 주기적으로 만료시킨다. */
@Component
public class OrderExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationJob.class);

    private final OrderRepository orders;
    private final OrderService service;

    public OrderExpirationJob(OrderRepository orders, OrderService service) {
        this.orders = orders;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${order.expiration.poll-interval-ms:500}")
    public void expireOverdueOrders() {
        List<Long> ids;
        try {
            ids = orders.findOverduePendingIds(Instant.now(), Limit.of(500));
        } catch (RuntimeException e) {
            log.warn("Failed to look up overdue orders", e);
            return;
        }
        for (Long id : ids) {
            try {
                service.expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}", id, e);
            }
        }
    }
}
