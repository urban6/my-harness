package com.example.order.order;

import com.example.order.common.Times;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** R6: 결제 기한이 지난 주문을 주기적으로 만료시킨다. 주문마다 별도 트랜잭션으로 처리한다. */
@Component
public class OrderExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationJob.class);
    private static final int BATCH_SIZE = 200;

    private final OrderRepository orders;
    private final OrderService orderService;
    private final Clock clock;

    public OrderExpirationJob(OrderRepository orders, OrderService orderService, Clock clock) {
        this.orders = orders;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.expiration.poll-interval-ms:300}")
    public void expireOverdueOrders() {
        for (Long id : orders.findExpiredPendingIds(Times.now(clock), Limit.of(BATCH_SIZE))) {
            try {
                orderService.expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}", id, e);
            }
        }
    }
}
