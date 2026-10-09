package com.example.order.order;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 주문 1건 = 트랜잭션 1개. 결제가 락을 쥔 주문은 SKIP LOCKED로 건너뛴다. */
@Service
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderResourceSupport resources;

    public OrderExpiryService(OrderRepository orderRepository, OrderResourceSupport resources) {
        this.orderRepository = orderRepository;
        this.resources = resources;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(long orderId, Instant now) {
        return orderRepository.lockExpirable(orderId, now).map(order -> {
            resources.releaseReservation(order);
            order.expire();
            return true;
        }).orElse(false);
    }
}
