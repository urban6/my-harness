package com.example.order.common;

import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 RFC 9457 Problem Details(application/problem+json)로 내려준다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.code().status()).body(problem(ex.code(), ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        if (detail.isEmpty()) {
            detail = ex.getBindingResult().getAllErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        }
        return ResponseEntity.badRequest().body(problem(ErrorCode.VALIDATION_ERROR, detail));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR, badRequestDetail(ex, body));
            return ResponseEntity.badRequest().headers(headers).body(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String badRequestDetail(Exception ex, @Nullable Object body) {
        if (body instanceof ProblemDetail pd && pd.getDetail() != null) {
            return pd.getDetail();
        }
        return ex.getMessage() == null ? "Invalid request" : ex.getMessage();
    }

    public static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        return problem;
    }
}
