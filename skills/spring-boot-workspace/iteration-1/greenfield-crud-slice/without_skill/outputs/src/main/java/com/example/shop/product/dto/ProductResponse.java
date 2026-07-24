package com.example.shop.product.dto;

import com.example.shop.product.domain.Product;
import java.math.BigDecimal;
import java.time.Instant;

public class ProductResponse {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final int stockQuantity;
    private final Instant createdAt;

    public ProductResponse(Long id, String name, BigDecimal price, int stockQuantity, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.createdAt = createdAt;
    }

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getCreatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
