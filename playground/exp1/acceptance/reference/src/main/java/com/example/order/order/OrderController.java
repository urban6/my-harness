package com.example.order.order;

import com.example.order.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public record ItemRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }

    public record CreateOrderRequest(@NotEmpty List<@Valid @NotNull ItemRequest> items) {
    }

    public record ItemResponse(Long productId, int quantity, long unitPrice) {
    }

    public record OrderResponse(Long id, OrderStatus status, long totalPrice, List<ItemResponse> items, Instant createdAt) {
        static OrderResponse from(Order o) {
            List<ItemResponse> items = o.getItems().stream()
                    .map(i -> new ItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                    .toList();
            return new OrderResponse(o.getId(), o.getStatus(), o.getTotalPrice(), items, o.getCreatedAt());
        }
    }

    public record PageResponse(List<OrderResponse> content, int page, int size, long totalElements) {
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        List<OrderService.Line> lines = req.items().stream()
                .map(i -> new OrderService.Line(i.productId(), i.quantity()))
                .toList();
        Order order = service.place(lines);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId()))
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id) {
        return OrderResponse.from(service.cancel(id));
    }

    @GetMapping
    public PageResponse list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be >= 0 and size must be between 1 and 100");
        }
        Page<Order> result = service.list(page, size);
        return new PageResponse(result.map(OrderResponse::from).getContent(), page, size, result.getTotalElements());
    }
}
