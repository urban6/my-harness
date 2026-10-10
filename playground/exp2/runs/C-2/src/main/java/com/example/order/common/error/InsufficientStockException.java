package com.example.order.common.error;

public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String detail) {
        super(ErrorCode.INSUFFICIENT_STOCK, detail);
    }
}
