package com.example.order.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull @Positive Long price,
            @NotNull @PositiveOrZero Integer stock) {
    }

    public record ProductResponse(Long id, String name, long price, int stock, int reserved, int available) {

        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(),
                    p.getStock() - p.getReserved());
        }
    }
}
