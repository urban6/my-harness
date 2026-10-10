package com.example.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull @PositiveOrZero @Max(1_000_000_000L) Long price,
        @NotNull @PositiveOrZero Integer stock
) {}
