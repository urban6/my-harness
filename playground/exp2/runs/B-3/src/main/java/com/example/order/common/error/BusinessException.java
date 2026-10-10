package com.example.order.common.error;

/** 비즈니스 규칙 위반. HTTP 상태 매핑은 {@link GlobalExceptionHandler}가 담당한다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
