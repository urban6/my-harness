package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 결제 기한이 지난 PENDING_PAYMENT 주문을 EXPIRED 로 바꾸고 재고·쿠폰을 되돌린다. */
@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderService orderService;

    public OrderExpiryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval:PT1S}")
    void sweep() {
        for (Long id : orderService.findDueOrderIds()) {
            try {
                orderService.expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("주문 만료 처리 실패: id={}", id, e);
            }
        }
    }
}
