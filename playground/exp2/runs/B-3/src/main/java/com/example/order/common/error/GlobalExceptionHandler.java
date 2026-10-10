package com.example.order.common.error;

import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 RFC 9457 Problem Details(application/problem+json)로 응답한다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.example.com/problems/";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex) {
        return problem(statusOf(ex.getCode()), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "예상치 못한 오류가 발생했습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof org.springframework.validation.FieldError fe
                        ? fe.getField() + ": " + fe.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail);
    }

    /** Spring MVC 표준 예외(파싱 실패, 헤더 누락, 타입 불일치, 메서드 검증 등)를 공통 포맷으로 맞춘다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response == null) {
            return null;
        }
        ProblemDetail pd = response.getBody() instanceof ProblemDetail p
                ? p
                : ProblemDetail.forStatus(statusCode);
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            decorate(pd, ErrorCode.VALIDATION_ERROR);
        }
        return ResponseEntity.status(statusCode)
                .headers(response.getHeaders())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private ResponseEntity<Object> problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(pd, code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    private static void decorate(ProblemDetail pd, ErrorCode code) {
        pd.setType(URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        if (pd.getTitle() == null) {
            HttpStatus resolved = HttpStatus.resolve(pd.getStatus());
            pd.setTitle(resolved != null ? resolved.getReasonPhrase() : "Error");
        }
        if (pd.getDetail() == null) {
            pd.setDetail(pd.getTitle());
        }
        pd.setProperty("code", code.name());
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case PAYMENT_DECLINED -> HttpStatus.PAYMENT_REQUIRED;
            case PRODUCT_NOT_FOUND, COUPON_NOT_FOUND, ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_STOCK, COUPON_NOT_APPLICABLE, COUPON_EXHAUSTED, DUPLICATE_COUPON_CODE,
                 INVALID_STATE, IDEMPOTENCY_IN_PROGRESS -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PAYMENT_GATEWAY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
