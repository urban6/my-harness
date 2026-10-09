package com.example.order.common.error;

/** HTTP를 모르는 도메인 예외. 상태코드 매핑은 {@link GlobalExceptionHandler}가 한다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
