package com.example.order.order;

import com.example.order.common.Times;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 결제 대기 만료 스윕 (R6). 후보 조회는 락 없이, 처리는 주문당 트랜잭션 1개. */
@Component
public class OrderExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Clock clock;

    public OrderExpirySweeper(OrderRepository orderRepository, OrderService orderService, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-delay-ms:200}")
    public void sweep() {
        List<Long> ids;
        try {
            ids = orderRepository.findDueIds(OrderStatus.PENDING_PAYMENT, Times.now(clock), PageRequest.of(0, 100));
        } catch (RuntimeException e) {
            log.warn("expiry sweep: candidate query failed", e);
            return;
        }
        for (Long id : ids) {
            try {
                orderService.expireIfDue(id);
            } catch (RuntimeException e) {
                log.warn("expiry sweep: failed to expire order {}", id, e);
            }
        }
    }
}
