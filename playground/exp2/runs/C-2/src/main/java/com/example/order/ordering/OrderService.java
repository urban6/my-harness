package com.example.order.ordering;

import com.example.order.common.error.OrderNotFoundException;
import com.example.order.ordering.dto.OrderPageResponse;
import com.example.order.ordering.dto.OrderResponse;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderQueryRepository orderQueryRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderQueryRepository = orderQueryRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public OrderPageResponse list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        List<Order> rows = orderQueryRepository.findPage(userId, status, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        List<OrderResponse> content = page.stream().map(OrderResponse::from).toList();
        String nextCursor = hasNext ? OrderCursor.of(page.get(page.size() - 1)).encode() : null;
        return new OrderPageResponse(content, nextCursor);
    }

    @Transactional
    public OrderResponse ship(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        order.ship(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        order.deliver(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return OrderResponse.from(order);
    }

    private static OrderNotFoundException notFound(long id) {
        return new OrderNotFoundException("주문 " + id + "을(를) 찾을 수 없습니다.");
    }
}
