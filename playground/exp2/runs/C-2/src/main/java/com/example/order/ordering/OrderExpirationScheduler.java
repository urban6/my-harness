package com.example.order.ordering;

import com.example.order.common.config.OrderProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 결제 대기 만료 (R6). 주문마다 별도 트랜잭션 + SKIP LOCKED (01 설계 8). */
@Component
@ConditionalOnProperty(name = "order.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationScheduler.class);

    private final OrderRepository orderRepository;
    private final OrderEffects effects;
    private final OrderProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderExpirationScheduler(OrderRepository orderRepository, OrderEffects effects,
            OrderProperties properties, Clock clock, TransactionTemplate tx) {
        this.orderRepository = orderRepository;
        this.effects = effects;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${order.expiration.scan-interval-ms:500}")
    public void expireOverdueOrders() {
        int batch = properties.expiration().batchSize();
        try {
            while (true) {
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                List<Long> ids = orderRepository.findExpiredIds(OrderStatus.PENDING_PAYMENT, now, PageRequest.of(0, batch));
                int expired = 0;
                for (Long id : ids) {
                    try {
                        if (Boolean.TRUE.equals(tx.execute(status -> expireOne(id)))) {
                            expired++;
                        }
                    } catch (RuntimeException e) {
                        log.error("Failed to expire order {}", id, e);
                    }
                }
                if (ids.size() < batch || expired == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("Order expiration scan failed", e);
        }
    }

    private boolean expireOne(long id) {
        // 결제/취소가 처리 중이면 empty -> 다음 회차에 다시 본다
        Order order = orderRepository.lockForExpiration(id).orElse(null);
        if (order == null) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getExpiresAt().isAfter(now)) {
            return false;
        }
        effects.releaseReservation(order);
        effects.releaseCoupon(order);
        order.expire(now);
        return true;
    }
}
