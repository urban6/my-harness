package com.example.order.product;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, int requested) {
        super("재고가 부족합니다: productId=" + productId + ", 요청 수량=" + requested);
    }
}
