package com.example.order.product;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(Long productId) {
        super(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다: productId=" + productId);
    }
}
