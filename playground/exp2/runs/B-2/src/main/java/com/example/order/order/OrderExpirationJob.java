package com.example.order.order;

import java.time.Instant;
import java.util.List;

import com.example.order.common.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 기한이 지난 PENDING_PAYMENT 주문을 EXPIRED로 바꾸고 예약·쿠폰을 복원한다(R6).
 * 주문마다 별도 트랜잭션으로 처리해 상품 잠금이 주문 사이에 쌓이지 않게 한다.
 */
@Component
public class OrderExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationJob.class);
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderInventory inventory;
    private final TransactionTemplate transactionTemplate;

    public OrderExpirationJob(OrderRepository orderRepository, OrderInventory inventory,
                              TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.inventory = inventory;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${order.expiration-interval}")
    public void expireOverdueOrders() {
        Instant now = Times.now();
        List<Long> ids = orderRepository.findExpiredPendingIds(now, BATCH_SIZE);
        for (Long id : ids) {
            try {
                transactionTemplate.executeWithoutResult(status -> expire(id, now));
            } catch (RuntimeException e) {
                log.warn("주문 만료 처리 실패: id={}", id, e);
            }
        }
    }

    private void expire(Long id, Instant now) {
        orderRepository.lockIfExpiredPending(id, now).ifPresent(order -> {
            order.expire();
            inventory.releaseReservations(order);
            inventory.restoreCoupon(order);
        });
    }
}
