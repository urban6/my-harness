package com.example.order.web.error;

import java.util.List;

/** 계약에 정의된 오류(code 보유)를 나타내는 도메인 예외. */
public class ApiException extends RuntimeException {

    public record FieldError(String field, String message) {
    }

    private final ErrorCode code;
    private final List<FieldError> errors;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public ApiException(ErrorCode code, String detail, List<FieldError> errors) {
        super(detail, null, false, false); // 스택 트레이스 불필요 (흐름 제어용 예외)
        this.code = code;
        this.errors = errors;
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public static ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
    }
}
