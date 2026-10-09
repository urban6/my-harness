package com.example.order.order;

import com.example.order.common.time.TimeProvider;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);
    private static final int BATCH_LIMIT = 200;

    private final OrderRepository orderRepository;
    private final OrderExpiryService expiryService;
    private final TimeProvider time;

    public OrderExpiryScheduler(OrderRepository orderRepository, OrderExpiryService expiryService, TimeProvider time) {
        this.orderRepository = orderRepository;
        this.expiryService = expiryService;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-delay-ms:250}")
    public void sweep() {
        Instant now = time.now();
        List<Long> ids = orderRepository.findExpirableIds(now, BATCH_LIMIT);
        for (Long id : ids) {
            try {
                expiryService.expireIfDue(id, now);
            } catch (RuntimeException e) {
                log.warn("주문 만료 처리 실패: id={}", id, e);
            }
        }
    }
}
