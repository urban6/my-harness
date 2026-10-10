package com.example.order.common;

import com.example.order.payment.PaymentGatewayUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/** 모든 오류를 RFC 9457 Problem Details(application/problem+json)로 변환한다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return problem(e.code(), e.getMessage());
    }

    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleGateway(PaymentGatewayUnavailableException e) {
        log.warn("Payment gateway unavailable: {}", e.getMessage());
        return problem(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        return problem(ErrorCode.VALIDATION_ERROR, "Malformed or unreadable request body");
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
        return problem(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleOther(Exception e) {
        if (e instanceof ErrorResponse er) {
            // 라우팅 실패(404/405 등) 같은 Spring MVC 표준 오류
            HttpStatusCode status = er.getStatusCode();
            ProblemDetail body = er.getBody();
            body.setProperty("code", "HTTP_" + status.value());
            return ResponseEntity.status(status).headers(er.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
        }
        log.error("Unexpected error", e);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(500), "Unexpected server error");
        body.setTitle("Internal Server Error");
        body.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(500).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail == null ? code.title() : detail);
        body.setType(URI.create(code.typeUri()));
        body.setTitle(code.title());
        body.setProperty("code", code.name());
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
