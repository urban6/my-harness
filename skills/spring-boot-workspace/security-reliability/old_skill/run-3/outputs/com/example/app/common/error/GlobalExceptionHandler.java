package com.example.app.common.error;

import com.example.app.member.EmailAlreadyExistsException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 에러 응답을 RFC 9457(application/problem+json)으로 통일하는 전역 핸들러.
 * 상태코드 매핑은 이 한 곳에 모은다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 — 이메일 중복
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailConflict(EmailAlreadyExistsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflict");
        pd.setType(URI.create("https://example.com/problems/email-already-exists"));
        return pd;
    }

    // 409 — 동시성으로 존재 검사를 통과한 중복이 DB 유니크 제약에 걸린 경우.
    // check-then-act 경합에서도 응답 포맷을 409로 일관되게 유지한다.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "이미 등록된 리소스입니다.");
        pd.setTitle("Conflict");
        pd.setType(URI.create("https://example.com/problems/data-integrity-violation"));
        return pd;
    }

    // 400 — Bean Validation 실패(이메일 형식 오류, 필수값 누락 등)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "요청 검증에 실패했습니다.");
        pd.setTitle("Validation Failed");
        pd.setType(URI.create("https://example.com/problems/validation-failed"));

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("errors", errors);
        return pd;
    }

    // 500 — 최후의 방어. 내부 메시지·스택트레이스를 노출하지 않는다.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // 상세는 서버 로그로, 응답에는 일반 메시지만.
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예상치 못한 오류가 발생했습니다.");
    }
}
