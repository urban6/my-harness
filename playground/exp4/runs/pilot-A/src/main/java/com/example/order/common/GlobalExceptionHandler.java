package com.example.order.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 RFC 9457 Problem Details(application/problem+json)로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException e, WebRequest request) {
        HttpStatus status = e.code().status();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        body.setProperty("code", e.code().name());
        return handleExceptionInternal(e, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
        log.error("Unhandled exception", e);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        body.setProperty("code", "INTERNAL_ERROR");
        return handleExceptionInternal(e, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem;
        if (body instanceof ProblemDetail pd) {
            problem = pd;
        } else {
            problem = ProblemDetail.forStatus(statusCode);
        }
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        problem.setTitle(resolved != null ? resolved.getReasonPhrase() : "Error");
        if (problem.getDetail() == null) {
            problem.setDetail(ex.getMessage() != null ? ex.getMessage() : problem.getTitle());
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", statusCode.value() == 400 ? ErrorCode.VALIDATION_ERROR.name()
                    : resolved != null ? resolved.name() : "ERROR");
        }
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, out, statusCode);
    }
}
