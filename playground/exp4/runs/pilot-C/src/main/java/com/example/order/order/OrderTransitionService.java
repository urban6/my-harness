package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 배송 상태 전이 (R8). 재고·쿠폰 변화 없음. */
@Service
public class OrderTransitionService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderTransitionService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse ship(Long id) {
        return transition(id, OrderStatus.PAID, OrderStatus.SHIPPED);
    }

    @Transactional
    public OrderResponse deliver(Long id) {
        return transition(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    private OrderResponse transition(Long id, OrderStatus from, OrderStatus to) {
        OrderEntity order = orderRepository.lockById(id).orElseThrow(() -> OrderReader.notFound(id));
        Instant now = Times.now(clock);
        if (order.hasActiveGatewayCall(now) || order.getStatus() != from) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Order is " + order.getStatus() + "; expected " + from + ".");
        }
        order.changeStatus(to, now);
        return OrderResponse.from(order);
    }
}
