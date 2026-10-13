package com.example.order.order;

public class InsufficientStockException extends RuntimeException {

    private final Long productId;

    public InsufficientStockException(Long productId, int requested) {
        super("재고가 부족합니다: productId=" + productId + ", 요청 수량=" + requested);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
