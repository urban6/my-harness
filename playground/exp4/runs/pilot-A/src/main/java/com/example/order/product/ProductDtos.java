package com.example.order.product;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(String name, Long price, Long stock) {
    }

    public record ProductResponse(Long id, String name, long price, long stock, long reserved, long available) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.available());
        }
    }
}
