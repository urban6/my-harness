package com.example.order.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Min(0) @Max(1_000_000_000L) Long price,
            @NotNull @Min(0) @Max(1_000_000_000L) Long stock) {
    }

    public record ProductResponse(Long id, String name, long price, int stock, int reserved, int available) {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(),
                    p.getStock() - p.getReserved());
        }
    }
}
