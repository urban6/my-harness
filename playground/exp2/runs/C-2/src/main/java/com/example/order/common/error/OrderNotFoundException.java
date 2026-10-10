package com.example.order.common.error;

public class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(String detail) {
        super(ErrorCode.ORDER_NOT_FOUND, detail);
    }
}
