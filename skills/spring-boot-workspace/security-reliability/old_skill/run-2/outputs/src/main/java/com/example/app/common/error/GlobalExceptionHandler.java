package com.example.app.common.error;

import com.example.app.member.DuplicateEmailException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 핸들러. 모든 에러 응답을 RFC 9457 ProblemDetail
 * (application/problem+json)로 일관되게 통일한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 — 이메일 중복
    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmail(DuplicateEmailException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflict");
        pd.setType(URI.create("https://example.com/problems/duplicate-email"));
        return pd;
    }

    // 400 — Bean Validation 실패(이메일 형식 오류, 필수값 누락 등)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "요청 검증에 실패했습니다.");
        pd.setTitle("Validation Failed");
        pd.setType(URI.create("https://example.com/problems/validation-error"));

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("errors", errors);   // 필드별 오류(확장 필드)
        return pd;
    }

    // 500 — 최후의 방어. 내부 메시지·스택트레이스를 노출하지 않는다.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예상치 못한 오류가 발생했습니다.");
    }
}
