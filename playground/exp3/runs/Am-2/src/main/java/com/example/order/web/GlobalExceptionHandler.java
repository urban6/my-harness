package com.example.order.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Spring MVC's own errors are already RFC 9457 via the base class; this adds the domain ones. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return problem(e.getStatus(), e.getTitle(), e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "Request conflicts with current state");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleAny(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
