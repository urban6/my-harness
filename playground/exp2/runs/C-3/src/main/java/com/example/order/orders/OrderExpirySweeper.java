package com.example.order.orders;

import com.example.order.common.time.Times;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);
    private static final int BATCH_SIZE = 200;

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Clock clock;

    public OrderExpirySweeper(OrderRepository orderRepository, OrderService orderService, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval-ms:500}")
    public void sweep() {
        Instant now = Times.now(clock);
        List<Long> ids;
        try {
            ids = orderRepository.findExpiredIds(now, BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("만료 대상 조회 실패: {}", e.toString());
            return;
        }
        for (Long id : ids) {
            try {
                orderService.expire(id, now); // 주문 1건당 개별 트랜잭션
            } catch (RuntimeException e) {
                log.warn("주문 만료 처리 실패: id={}", id, e);
            }
        }
    }
}
