package com.example.order.common.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.order.order.AmountOverflowException;
import com.example.order.order.InsufficientStockException;
import com.example.order.order.OrderAlreadyCancelledException;
import com.example.order.order.OrderNotFoundException;
import com.example.order.product.ProductNotFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 모든 에러를 RFC 9457 ProblemDetail로 변환하고 Content-Type을 application/problem+json으로 고정한다.
 * (클라이언트 Accept와 무관)
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- Content-Type 고정: 상속한 Spring 기본 처리(404 경로, 405, 415 등)도 이 지점을 지난다.
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(headers);
        copy.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(body, copy, statusCode);
    }

    private ResponseEntity<Object> problem(HttpStatus status, URI type, String title, String detail,
            WebRequest request, Map<String, Object> extensions) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        if (request instanceof ServletWebRequest swr) {
            pd.setInstance(URI.create(swr.getRequest().getRequestURI()));
        }
        extensions.forEach(pd::setProperty);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private ResponseEntity<Object> problem(HttpStatus status, URI type, String title, String detail,
            WebRequest request) {
        return problem(status, type, title, detail, request, Map.of());
    }

    // ---- 400 : 검증 / 파싱 / 타입 변환

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            errors.add(Map.of("field", field, "message", messageOf(error)));
        }
        return validationProblem(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                String field = error instanceof FieldError fe ? fe.getField() : name;
                errors.add(Map.of("field", field == null ? "" : field, "message", messageOf(error)));
            });
        });
        return validationProblem(errors, request);
    }

    /** @Validated가 붙은 경우에 대한 방어적 처리. */
    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex,
            WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            errors.add(Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()));
        }
        return validationProblem(errors, request);
    }

    private ResponseEntity<Object> validationProblem(List<Map<String, String>> errors, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation Failed",
                "요청 검증에 실패했습니다.", request, Map.of("errors", errors));
    }

    private static String messageOf(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? "유효하지 않은 값입니다" : message;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Jackson 내부 메시지는 노출하지 않는다.
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST, "Malformed Request Body",
                "요청 본문을 해석할 수 없습니다.", request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String parameter = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        Map<String, Object> ext = parameter == null ? Map.of() : Map.of("parameter", parameter);
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PARAMETER, "Invalid Parameter",
                "요청 파라미터의 형식이 올바르지 않습니다.", request, ext);
    }

    @ExceptionHandler(AmountOverflowException.class)
    protected ResponseEntity<Object> handleAmountOverflow(AmountOverflowException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.AMOUNT_OVERFLOW, "Amount Overflow",
                ex.getMessage(), request);
    }

    // ---- 404

    @ExceptionHandler(ProductNotFoundException.class)
    protected ResponseEntity<Object> handleProductNotFound(ProductNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.PRODUCT_NOT_FOUND, "Product Not Found",
                ex.getMessage(), request, Map.of("productIds", ex.getProductIds()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    protected ResponseEntity<Object> handleOrderNotFound(OrderNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.ORDER_NOT_FOUND, "Order Not Found",
                ex.getMessage(), request);
    }

    // ---- 409

    @ExceptionHandler(InsufficientStockException.class)
    protected ResponseEntity<Object> handleInsufficientStock(InsufficientStockException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.INSUFFICIENT_STOCK, "Insufficient Stock",
                ex.getMessage(), request, Map.of("productId", ex.getProductId()));
    }

    @ExceptionHandler(OrderAlreadyCancelledException.class)
    protected ResponseEntity<Object> handleAlreadyCancelled(OrderAlreadyCancelledException ex,
            WebRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.ORDER_ALREADY_CANCELLED, "Order Already Cancelled",
                ex.getMessage(), request);
    }

    // ---- 500 : 최후의 방어. 내부 정보는 로그에만.

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal Server Error",
                "예상치 못한 오류가 발생했습니다.", request);
    }
}
