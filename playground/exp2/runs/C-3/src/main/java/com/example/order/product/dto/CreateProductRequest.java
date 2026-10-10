package com.example.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String name,
        @NotNull @Min(1) @Max(10_000_000) Long price,
        @NotNull @Min(0) @Max(1_000_000) Integer stock) {
}
