package com.example.order.common;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setType(URI.create(code.typeUri()));
        body.setTitle(code.title());
        body.setProperty("code", code.name());
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return problem(e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                .map(err -> err instanceof org.springframework.validation.FieldError fe
                        ? fe.getField() + ": " + fe.getDefaultMessage()
                        : err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.VALIDATION_ERROR, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        return problem(ErrorCode.VALIDATION_ERROR, "Malformed or unreadable request body");
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
        return problem(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException e) {
        return problem(ErrorCode.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException e) {
        return problem(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }
}
