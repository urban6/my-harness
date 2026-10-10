package com.example.order.common;

/** 도메인 오류. GlobalExceptionHandler 가 RFC 9457 응답으로 변환한다. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail, null, false, false);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
    }
}
