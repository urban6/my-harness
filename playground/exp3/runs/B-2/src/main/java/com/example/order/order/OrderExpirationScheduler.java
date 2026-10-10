package com.example.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationScheduler.class);

    private final OrderService orderService;

    public OrderExpirationScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval:PT10S}")
    void sweep() {
        try {
            int expired = orderService.expireDueOrders();
            if (expired > 0) {
                log.info("결제 기한이 지난 주문 {}건을 만료 처리했습니다.", expired);
            }
        } catch (RuntimeException e) {
            log.warn("주문 만료 처리 실패, 다음 주기에 재시도합니다.", e);
        }
    }
}
