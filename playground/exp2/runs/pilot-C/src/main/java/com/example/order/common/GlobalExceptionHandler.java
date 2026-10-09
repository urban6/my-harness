package com.example.order.common;

import com.example.order.payment.PaymentGatewayUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 9457 Problem Details. Content-Type 은 항상 application/problem+json 으로 명시한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.code(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<ProblemDetail> handlePg(PaymentGatewayUnavailableException ex, HttpServletRequest req) {
        return build(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "payment gateway is unavailable", req, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, "request body is missing or malformed", req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleNotValid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(e -> errors.add(fieldError(e)));
        return build(ErrorCode.VALIDATION_ERROR, "request validation failed", req, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest req) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> {
            Map<String, String> m = fieldError(e);
            if ("".equals(m.get("field")) && r.getMethodParameter().getParameterName() != null) {
                m = Map.of("field", r.getMethodParameter().getParameterName(), "message", m.get("message"));
            }
            errors.add(m);
        }));
        return build(ErrorCode.VALIDATION_ERROR, "request validation failed", req, errors);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleMissing(Exception ex, HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleMismatch(MethodArgumentTypeMismatchException ex,
                                                        HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, "invalid value for '" + ex.getName() + "'", req, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, ex.getMessage(), req, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, "unsupported content type", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String constraint = constraintName(ex);
        if ("uk_coupons_code".equals(constraint)) {
            return build(ErrorCode.DUPLICATE_COUPON_CODE, "coupon code already exists", req, null);
        }
        if ("uk_orders_coupon_user_active".equals(constraint)) {
            return build(ErrorCode.COUPON_NOT_APPLICABLE, "coupon is already in use by this user", req, null);
        }
        log.error("unexpected data integrity violation", ex);
        return internal(ex, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleOther(Exception ex, HttpServletRequest req) {
        if (ex instanceof ErrorResponse er) {
            ProblemDetail pd = er.getBody();
            pd.setInstance(URI.create(req.getRequestURI()));
            return ResponseEntity.status(er.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
        }
        log.error("unhandled exception", ex);
        return internal(ex, req);
    }

    private ResponseEntity<ProblemDetail> internal(Exception ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
        pd.setTitle("Internal server error");
        pd.setType(URI.create("https://example.com/problems/internal-error"));
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, HttpServletRequest req,
                                                List<Map<String, String>> errors) {
        HttpStatusCode status = code.status();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code.name());
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    private static Map<String, String> fieldError(MessageSourceResolvable e) {
        String field = e instanceof FieldError fe ? fe.getField() : "";
        String msg = e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage();
        return Map.of("field", field, "message", msg);
    }

    private static String constraintName(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase();
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null) {
                String lower = m.toLowerCase();
                if (lower.contains("uk_coupons_code")) return "uk_coupons_code";
                if (lower.contains("uk_orders_coupon_user_active")) return "uk_orders_coupon_user_active";
            }
        }
        return null;
    }
}
