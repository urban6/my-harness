package com.example.order.common;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 RFC 9457 application/problem+json (type,title,status,detail,code) 으로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        return problem(ex.code(), ex.getMessage(), null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return problem(ErrorCode.VALIDATION_ERROR, ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(ErrorCode.INTERNAL_ERROR, "Unexpected error.", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(e -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("field", e instanceof FieldError fe ? fe.getField() : e.getObjectName());
            m.put("message", e.getDefaultMessage());
            errors.add(m);
        });
        return problem(ErrorCode.VALIDATION_ERROR, "Request validation failed.", errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (MessageSourceResolvable e : ex.getAllErrors()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("message", e.getDefaultMessage());
            errors.add(m);
        }
        return problem(ErrorCode.VALIDATION_ERROR, "Request validation failed.", errors);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail pd = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(statusCode);
        int value = statusCode.value();
        String code = codeFor(value);
        if (value == 400) {
            pd.setType(URI.create(ErrorCode.VALIDATION_ERROR.typeUri()));
            pd.setTitle(ErrorCode.VALIDATION_ERROR.title());
        } else if (value >= 500) {
            log.error("Server error", ex);
            pd.setDetail("Unexpected error.");
        } else {
            pd.setType(URI.create("https://example.com/problems/" + code.toLowerCase().replace('_', '-')));
        }
        if (pd.getTitle() == null) {
            HttpStatus hs = HttpStatus.resolve(value);
            pd.setTitle(hs != null ? hs.getReasonPhrase() : "Error");
        }
        if (pd.getDetail() == null) {
            pd.setDetail(pd.getTitle());
        }
        pd.setProperty("code", code);
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> ErrorCode.VALIDATION_ERROR.name();
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> ErrorCode.INTERNAL_ERROR.name();
            default -> {
                HttpStatus hs = HttpStatus.resolve(status);
                yield hs != null ? hs.name() : "ERROR";
            }
        };
    }

    private static ResponseEntity<Object> problem(ErrorCode code, String detail, List<Map<String, String>> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}
