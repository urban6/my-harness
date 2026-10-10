package com.example.order.common.error;

import java.util.List;

/** 400 VALIDATION_ERROR. 필드 단위 오류 목록(errors[])을 함께 실어 나른다. */
public class RequestValidationException extends BusinessException {

    public record FieldIssue(String field, String message) {
    }

    private final List<FieldIssue> errors;

    public RequestValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.errors = List.of(new FieldIssue(field, message));
    }

    public List<FieldIssue> getErrors() {
        return errors;
    }
}
