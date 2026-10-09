package com.example.order.order.dto;

import com.example.order.order.OrderStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        long id,
        OrderStatus status,
        long totalPrice,
        List<OrderItemResponse> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant createdAt) {}
