package com.example.order.product.dto;

import com.example.order.product.Product;

public record ProductResponse(long id, String name, long price, int stock) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}
