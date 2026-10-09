package com.example.order.common;

public class ApiException extends RuntimeException {
    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
