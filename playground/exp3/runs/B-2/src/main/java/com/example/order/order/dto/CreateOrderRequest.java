package com.example.order.order.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotEmpty @Valid List<Item> items,
        String couponCode
) {
    public record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {}
}
