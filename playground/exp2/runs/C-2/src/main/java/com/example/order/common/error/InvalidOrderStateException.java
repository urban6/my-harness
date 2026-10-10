package com.example.order.common.error;

public class InvalidOrderStateException extends BusinessException {
    public InvalidOrderStateException(String detail) {
        super(ErrorCode.INVALID_STATE, detail);
    }
}
