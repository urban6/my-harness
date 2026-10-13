package com.example.order.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank(message = "name은 필수이며 공백만으로 구성될 수 없습니다.")
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        String name,

        @NotNull(message = "price는 필수입니다.")
        @Positive(message = "price는 0보다 커야 합니다.")
        Long price,

        @NotNull(message = "stock은 필수입니다.")
        @PositiveOrZero(message = "stock은 0 이상이어야 합니다.")
        Integer stock
) {
}
