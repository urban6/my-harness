package com.example.order.order.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotEmpty @DistinctProductIds List<@NotNull @Valid OrderItemRequest> items) {
}
