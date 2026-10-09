package com.example.order.common.error;

public class InvalidStateException extends ApiException {

    public InvalidStateException(String detail) {
        super(ErrorCode.INVALID_STATE, detail);
    }
}
