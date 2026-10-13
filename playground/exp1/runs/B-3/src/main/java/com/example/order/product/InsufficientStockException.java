package com.example.order.product;

import com.example.order.common.error.BusinessConflictException;

public class InsufficientStockException extends BusinessConflictException {

    public InsufficientStockException(Long productId, int stock, int requested) {
        super("재고가 부족합니다: productId=" + productId + ", stock=" + stock + ", requested=" + requested);
    }
}
