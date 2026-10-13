package com.example.order.common.error;

import com.example.order.order.OrderAlreadyCancelledException;
import com.example.order.order.OrderNotFoundException;
import com.example.order.product.InsufficientStockException;
import com.example.order.product.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 에러를 RFC 9457 ProblemDetail(application/problem+json)로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 프레임워크 표준 예외 오버라이드 ----

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST, "Malformed Request",
                "요청 본문을 해석할 수 없습니다. JSON 형식과 필드 타입을 확인하세요.");
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            errors.add(Map.of("field", field, "message", String.valueOf(error.getDefaultMessage())));
        }
        ProblemDetail pd = validationProblem(errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(Map.of("field", field == null ? "unknown" : field,
                        "message", String.valueOf(error.getDefaultMessage())));
            }
        });
        ProblemDetail pd = validationProblem(errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PARAMETER, "Invalid Parameter",
                "요청 파라미터 '" + name + "'의 형식이 올바르지 않습니다.");
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** 모든 ProblemDetail 응답의 Content-Type을 application/problem+json으로 고정한다. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders forced = new HttpHeaders();
        forced.putAll(headers);
        forced.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.createResponseEntity(body, forced, statusCode, request);
    }

    // ---- 도메인 예외 ----

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException ex,
            HttpServletRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.PRODUCT_NOT_FOUND, "Product Not Found",
                ex.getMessage(), request));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(OrderNotFoundException ex,
            HttpServletRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.ORDER_NOT_FOUND, "Order Not Found",
                ex.getMessage(), request));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException ex,
            HttpServletRequest request) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.INSUFFICIENT_STOCK, "Insufficient Stock",
                ex.getMessage(), request));
    }

    @ExceptionHandler(OrderAlreadyCancelledException.class)
    public ResponseEntity<ProblemDetail> handleAlreadyCancelled(OrderAlreadyCancelledException ex,
            HttpServletRequest request) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.ORDER_ALREADY_CANCELLED,
                "Order Already Cancelled", ex.getMessage(), request));
    }

    // ---- 안전망 ----

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        ProblemDetail pd = validationProblem(errors);
        setInstance(pd, request);
        return respond(pd);
    }

    @ExceptionHandler(ArithmeticException.class)
    public ResponseEntity<ProblemDetail> handleArithmetic(ArithmeticException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation Failed",
                "금액 합계가 표현 범위를 초과합니다", request);
        pd.setProperty("errors", List.of());
        return respond(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "Internal Server Error", "예상치 못한 오류가 발생했습니다.", request));
    }

    // ---- 헬퍼 ----

    private ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation Failed",
                "요청 값이 유효하지 않습니다.");
        pd.setProperty("errors", errors);
        return pd;
    }

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        return pd;
    }

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail,
            HttpServletRequest request) {
        ProblemDetail pd = problem(status, type, title, detail);
        setInstance(pd, request);
        return pd;
    }

    private void setInstance(ProblemDetail pd, HttpServletRequest request) {
        try {
            pd.setInstance(URI.create(request.getRequestURI()));
        } catch (IllegalArgumentException ignored) {
            // instance는 선택 필드이므로 생략한다.
        }
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}
