package com.example.order.common.error;

public class InvalidCursorException extends ApiException {

    public InvalidCursorException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}
