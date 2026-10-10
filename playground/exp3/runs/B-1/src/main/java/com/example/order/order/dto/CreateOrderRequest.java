package com.example.order.order.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items,
        @Size(min = 1, max = 100) String couponCode
) {

    public record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {}
}
