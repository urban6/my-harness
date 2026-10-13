package com.example.order.orders;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(@NotEmpty List<@NotNull @Valid Item> items) {

    public record Item(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }
}
