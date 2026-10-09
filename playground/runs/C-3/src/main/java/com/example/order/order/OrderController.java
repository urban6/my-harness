package com.example.order.order;

import com.example.order.common.web.PageResponse;
import com.example.order.order.dto.CreateOrderRequest;
import com.example.order.order.dto.OrderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 주의: 클래스에 @Validated를 붙이지 않는다. page/size 검증은 Spring 내장 메서드 검증
 * (HandlerMethodValidationException)에 맡긴다 (01 §3 R6).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse body = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") Long id) {
        return orderService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable("id") Long id) {
        return orderService.cancel(id);
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(name = "page", defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다") int page,
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "1 이상이어야 합니다")
            @Max(value = 100, message = "100 이하여야 합니다") int size) {
        return orderService.list(page, size);
    }
}
