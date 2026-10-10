package com.example.order.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotNull @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items,
        @Size(min = 1, max = 64) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String couponCode) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(10_000) Integer quantity) {
    }
}
