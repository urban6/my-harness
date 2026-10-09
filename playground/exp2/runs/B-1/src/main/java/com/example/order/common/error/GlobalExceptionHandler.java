package com.example.order.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 9457 Problem Details 로 내보낸다. 필드: type, title, status, detail, code.
 * 스프링 MVC 표준 예외(본문 파싱 실패, 헤더 누락, 검증 실패, 타입 변환 실패)는 부모 클래스가 잡고,
 * {@link #handleExceptionInternal}에서 code 를 채운다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex) {
        HttpStatus status = statusOf(ex.getCode());
        return problem(ProblemDetail.forStatusAndDetail(status, ex.getMessage()), ex.getCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예상치 못한 오류가 발생했습니다.");
        return problem(pd, ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        pd.setDetail("요청 검증에 실패했습니다.");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail pd = body instanceof ProblemDetail p
                ? p
                : ProblemDetail.forStatusAndDetail(statusCode, ex.getMessage());
        if (pd.getDetail() == null) {
            pd.setDetail(HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
        }
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status == HttpStatus.BAD_REQUEST) {
            decorate(pd, ErrorCode.VALIDATION_ERROR);
        } else {
            // 405·415 등 계약 밖의 프레임워크 오류: 상태 이름을 code 로 쓴다.
            decorate(pd, status.name(), status.getReasonPhrase());
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private ResponseEntity<Object> problem(ProblemDetail pd, ErrorCode code) {
        decorate(pd, code);
        return ResponseEntity.status(pd.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private static void decorate(ProblemDetail pd, ErrorCode code) {
        decorate(pd, code.name(), titleOf(code, pd.getStatus()));
    }

    private static void decorate(ProblemDetail pd, String code, String title) {
        String slug = code.toLowerCase(Locale.ROOT).replace('_', '-');
        pd.setType(URI.create("/problems/" + slug));
        pd.setTitle(title);
        pd.setProperty("code", code);
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

    private static String titleOf(ErrorCode code, int status) {
        return switch (code) {
            case VALIDATION_ERROR -> "Validation Failed";
            case PAYMENT_DECLINED -> "Payment Declined";
            case PRODUCT_NOT_FOUND -> "Product Not Found";
            case COUPON_NOT_FOUND -> "Coupon Not Found";
            case ORDER_NOT_FOUND -> "Order Not Found";
            case INSUFFICIENT_STOCK -> "Insufficient Stock";
            case COUPON_NOT_APPLICABLE -> "Coupon Not Applicable";
            case COUPON_EXHAUSTED -> "Coupon Exhausted";
            case DUPLICATE_COUPON_CODE -> "Duplicate Coupon Code";
            case INVALID_STATE -> "Invalid Order State";
            case IDEMPOTENCY_IN_PROGRESS -> "Idempotent Request In Progress";
            case IDEMPOTENCY_KEY_MISMATCH -> "Idempotency Key Mismatch";
            case PAYMENT_GATEWAY_UNAVAILABLE -> "Payment Gateway Unavailable";
            case INTERNAL_ERROR -> HttpStatus.valueOf(status).getReasonPhrase();
        };
    }
}
