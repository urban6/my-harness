package com.example.order.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://example.com/problems/";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex) {
        return build(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(jakarta.validation.ConstraintViolationException ex) {
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex) {
        String constraint = ConstraintNames.of(ex);
        if (ConstraintNames.UQ_COUPONS_CODE.equals(constraint)) {
            return build(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다.");
        }
        if (ConstraintNames.UX_ORDERS_ACTIVE_USER_COUPON.equals(constraint)) {
            return build(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다.");
        }
        log.error("Unmapped data integrity violation", ex);
        return build(ErrorCode.INTERNAL_ERROR, "Internal server error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, "Internal server error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setDetail("Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> base = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (base == null) {
            return null;
        }
        ProblemDetail pd = base.getBody() instanceof ProblemDetail p
                ? p
                : ProblemDetail.forStatusAndDetail(statusCode,
                        statusCode.value() >= 500 ? "Internal server error" : String.valueOf(ex.getMessage()));
        ErrorCode code = ErrorCode.fromStatus(statusCode.value());
        pd.setType(URI.create(TYPE_PREFIX + code.typeSlug()));
        pd.setTitle(reason(statusCode.value()));
        pd.setProperty("code", code.name());
        HttpHeaders h = new HttpHeaders();
        h.putAll(base.getHeaders());
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, h, statusCode);
    }

    private static ResponseEntity<Object> build(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status()), detail);
        pd.setType(URI.create(TYPE_PREFIX + code.typeSlug()));
        pd.setTitle(reason(code.status()));
        pd.setProperty("code", code.name());
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private static String reason(int status) {
        HttpStatus hs = HttpStatus.resolve(status);
        return hs != null ? hs.getReasonPhrase() : "Error";
    }
}
