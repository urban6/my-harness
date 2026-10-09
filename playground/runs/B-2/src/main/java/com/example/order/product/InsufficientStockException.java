package com.example.order.product;

import com.example.order.common.ConflictException;

public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(Long productId, int stock, int requested) {
        super("재고가 부족합니다: productId=" + productId + ", stock=" + stock + ", requested=" + requested);
    }
}
