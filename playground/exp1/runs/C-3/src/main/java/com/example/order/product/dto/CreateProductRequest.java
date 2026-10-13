package com.example.order.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateProductRequest(
        @NotBlank(message = "필수 값이며 공백일 수 없습니다") String name,
        @NotNull(message = "필수 값입니다") @Positive(message = "0보다 커야 합니다") Long price,
        @NotNull(message = "필수 값입니다") @PositiveOrZero(message = "0 이상이어야 합니다") Integer stock) {
}
