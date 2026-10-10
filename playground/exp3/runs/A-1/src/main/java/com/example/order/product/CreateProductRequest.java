package com.example.order.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateProductRequest(
        @NotBlank String name,
        @NotNull @PositiveOrZero Long price,
        @NotNull @PositiveOrZero Long stock) {
}
