package com.example.order.product.dto;

import com.example.order.product.Product;

public record ProductResponse(Long id, String name, long price, int stock, int reserved, int available) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(),
                product.getStock(), product.getReserved(), product.available());
    }
}
