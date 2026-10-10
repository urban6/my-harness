package com.example.order.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull @Min(0) @Max(1_000_000_000L) Long price,
        @NotNull @Min(0) @Max(1_000_000_000L) Integer stock) {

    public CreateProductRequest {
        // stored value is the trimmed name; validation (blank / length) applies to the trimmed value
        name = name == null ? null : name.strip();
    }
}
