package com.example.order.product.dto;

import com.example.order.product.Product;

public record ProductResponse(long id, String name, long price, int stock, int reserved, int available) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.available());
    }
}
