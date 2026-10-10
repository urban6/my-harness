package com.example.order.order;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotEmpty @Valid List<@NotNull Item> items,
        String couponCode) {

    public record Item(@NotNull Long productId, @NotNull @Min(1) Long quantity) {
    }
}
