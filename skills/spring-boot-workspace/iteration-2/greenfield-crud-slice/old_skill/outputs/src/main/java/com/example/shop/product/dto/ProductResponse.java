package com.example.shop.product.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        int stockQuantity,
        Instant createdAt
) {
}
