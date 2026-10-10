package com.example.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderCreateRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
        String couponCode) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1000) Long quantity) {
    }
}
