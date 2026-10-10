package com.example.order.web.dto;

import com.example.order.domain.Product;

public record ProductResponse(long id, String name, long price, long stock, long reserved, long available) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.getAvailable());
    }
}
