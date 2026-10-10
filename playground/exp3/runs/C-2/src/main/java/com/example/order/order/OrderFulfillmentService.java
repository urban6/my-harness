package com.example.order.order;

import com.example.order.common.InvalidOrderStateException;
import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.order.OrderDtos.OrderResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ship (PAID -> SHIPPED) and deliver (SHIPPED -> DELIVERED). */
@Service
public class OrderFulfillmentService {

    private final PurchaseOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderExpiryService expiryService;
    private final Clock clock;

    public OrderFulfillmentService(PurchaseOrderRepository orderRepository, OrderItemRepository itemRepository,
            OrderExpiryService expiryService, Clock clock) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.expiryService = expiryService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = InvalidOrderStateException.class)
    public OrderResponse ship(long orderId) {
        return transition(orderId, OrderStatus.PAID, OrderStatus.SHIPPED, "ship");
    }

    @Transactional(noRollbackFor = InvalidOrderStateException.class)
    public OrderResponse deliver(long orderId) {
        return transition(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED, "deliver");
    }

    private OrderResponse transition(long orderId, OrderStatus from, OrderStatus to, String action) {
        Instant now = TimeSupport.now(clock);
        PurchaseOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> Problems.orderNotFound(orderId));
        expiryService.expireLockedIfDue(order, now);
        if (order.getStatus() != from) {
            throw new InvalidOrderStateException(orderId, order.getStatus().name(), action);
        }
        order.transitionTo(to, now);
        return OrderResponse.of(order, itemRepository.findByOrderIdOrderByIdAsc(orderId));
    }
}
