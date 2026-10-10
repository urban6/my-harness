package com.example.order.web;

import com.example.order.gateway.PaymentGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return problem(e.getStatus(), e.getTitle(), e.getMessage());
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ResponseEntity<ProblemDetail> handleGateway(PaymentGatewayException e) {
        log.warn("payment gateway failure", e);
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", "payment gateway is unavailable or returned an invalid response");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "request conflicts with current state");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleAny(Exception e) {
        log.error("unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
