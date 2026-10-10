package com.example.order.common.error;

public class ProductNotFoundException extends BusinessException {
    public ProductNotFoundException(String detail) {
        super(ErrorCode.PRODUCT_NOT_FOUND, detail);
    }
}
