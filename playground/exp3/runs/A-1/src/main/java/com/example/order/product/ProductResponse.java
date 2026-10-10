package com.example.order.product;

public record ProductResponse(Long id, String name, long price, long stock, long reserved, long available) {

    static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(),
                p.getStock() - p.getReserved());
    }
}
