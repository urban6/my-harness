package com.example.order.order;

import com.example.order.common.Times;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 만료 처리 (R6). 스케줄러와 단건 지연 평가가 공유한다. */
@Service
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderOperations operations;
    private final Clock clock;

    public OrderExpiryService(OrderRepository orderRepository, OrderOperations operations, Clock clock) {
        this.orderRepository = orderRepository;
        this.operations = operations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredIds(int limit) {
        Instant now = Times.now(clock);
        return orderRepository.findExpiredIds(now, now.minus(OrderEntity.GATEWAY_INFLIGHT_TTL), PageRequest.of(0, limit));
    }

    /** 주문 락 후 상태·만료·표식을 재확인하고 만료시킨다. @return 실제로 만료시켰는가 */
    @Transactional
    public boolean expireOne(Long orderId) {
        OrderEntity order = orderRepository.lockById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        Instant now = Times.now(clock);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                || !order.isExpiredAt(now)
                || order.hasActiveGatewayCall(now)) {
            return false;
        }
        operations.expire(order, now);
        return true;
    }
}
