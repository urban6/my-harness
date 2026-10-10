package com.example.order.common.error;

/** HTTP를 모르는 도메인 예외의 공통 부모. ErrorCode -> 상태/제목 매핑은 GlobalExceptionHandler 한 곳에만 있다. */
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode code;

    protected BusinessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
