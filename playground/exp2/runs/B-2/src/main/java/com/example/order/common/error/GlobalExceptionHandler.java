package com.example.order.common.error;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = statusOf(code);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(code.title());
        decorate(problem, code.name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예상치 못한 오류가 발생했습니다.");
        decorate(problem, "INTERNAL_ERROR");
        return ResponseEntity.internalServerError().body(problem);
    }

    // 스프링 MVC 표준 예외(검증·바인딩·JSON 파싱 실패 등)도 같은 포맷으로 맞춘다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
                problem.setTitle(ErrorCode.VALIDATION_ERROR.title());
                decorate(problem, ErrorCode.VALIDATION_ERROR.name());
                List<String> errors = validationErrors(ex);
                if (!errors.isEmpty()) {
                    problem.setProperty("errors", errors);
                }
            } else {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                decorate(problem, status != null ? status.name() : "HTTP_" + statusCode.value());
            }
        }
        return response;
    }

    private static List<String> validationErrors(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException manv) {
            return manv.getBindingResult().getAllErrors().stream()
                    .map(e -> (e instanceof FieldError fe ? fe.getField() : e.getObjectName()) + ": " + e.getDefaultMessage())
                    .toList();
        }
        if (ex instanceof HandlerMethodValidationException hmve) {
            return hmve.getAllErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .toList();
        }
        return List.of();
    }

    private static void decorate(ProblemDetail problem, String code) {
        problem.setType(URI.create("urn:problem-type:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
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
        };
    }
}
