package com.example.order.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 숫자는 박싱 Long: int 범위 초과 허용, 누락은 @NotNull 로 400. */
public record ProductCreateRequest(
        @NotNull @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) @Max(10_000_000) Long price,
        @NotNull @Min(0) @Max(1_000_000) Long stock) {
}
