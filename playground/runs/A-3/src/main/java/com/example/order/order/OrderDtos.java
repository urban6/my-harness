package com.example.order.order;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.data.domain.Page;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(@NotEmpty List<@NotNull @Valid OrderItemRequest> items) {
    }

    public record OrderItemRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }

    public record OrderItemResponse(Long productId, int quantity, long unitPrice) {

        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice());
        }
    }

    public record OrderResponse(
            Long id, OrderStatus status, long totalPrice, List<OrderItemResponse> items, Instant createdAt) {

        static OrderResponse from(Order order) {
            List<OrderItemResponse> items = order.getItems().stream().map(OrderItemResponse::from).toList();
            return new OrderResponse(order.getId(), order.getStatus(), order.getTotalPrice(), items,
                    order.getCreatedAt());
        }
    }

    public record OrderPageResponse(List<OrderResponse> content, int page, int size, long totalElements) {

        static OrderPageResponse from(Page<Order> page) {
            List<OrderResponse> content = page.getContent().stream().map(OrderResponse::from).toList();
            return new OrderPageResponse(content, page.getNumber(), page.getSize(), page.getTotalElements());
        }
    }
}
