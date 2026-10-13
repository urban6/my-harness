package com.example.order.common.error;

import com.example.order.order.InsufficientStockException;
import com.example.order.order.OrderAlreadyCancelledException;
import com.example.order.order.OrderNotFoundException;
import com.example.order.product.ProductNotFoundException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
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

/**
 * 상태코드 매핑은 이 클래스 한 곳에서만 한다. 모든 에러 응답은 application/problem+json.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {}

    // ---------- 400: 프레임워크 예외 ----------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(this::toViolation)
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String name = result.getMethodParameter().getParameterName();
                    return result.getResolvableErrors().stream()
                            .map(err -> new FieldViolation(name, err.getDefaultMessage()));
                })
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        FieldViolation violation = new FieldViolation(name, name + " 값은 정수여야 합니다.");
        return handleExceptionInternal(
                ex, validationProblem(List.of(violation)), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String path = jsonPath(ex);
        String detail = path.isEmpty()
                ? "요청 본문을 JSON으로 해석할 수 없습니다."
                : "요청 본문의 '" + path + "' 값 형식이 올바르지 않습니다.";
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST,
                "Malformed Request Body", detail);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    // ---------- 404 / 409: 도메인 예외 ----------

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Object> handleProductNotFound(ProductNotFoundException ex, WebRequest request) {
        String ids = ex.getIds().stream().map(String::valueOf).collect(Collectors.joining(", "));
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.PRODUCT_NOT_FOUND, "Product Not Found",
                "상품을 찾을 수 없습니다: id=" + ids), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Object> handleOrderNotFound(OrderNotFoundException ex, WebRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.ORDER_NOT_FOUND, "Order Not Found",
                "주문을 찾을 수 없습니다: id=" + ex.getId()), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Object> handleInsufficientStock(InsufficientStockException ex, WebRequest request) {
        String detail = ex.getShortages().stream()
                .map(s -> "productId=" + s.productId() + " (요청 " + s.requested() + ", 재고 " + s.available() + ")")
                .collect(Collectors.joining(", ", "재고가 부족합니다: ", ""));
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.INSUFFICIENT_STOCK, "Insufficient Stock", detail),
                request);
    }

    @ExceptionHandler(OrderAlreadyCancelledException.class)
    public ResponseEntity<Object> handleAlreadyCancelled(OrderAlreadyCancelledException ex, WebRequest request) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.ORDER_ALREADY_CANCELLED,
                "Order Already Cancelled", "이미 취소된 주문입니다: id=" + ex.getId()), request);
    }

    // ---------- 500: 최후의 방어 (내부 정보 은닉) ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "Internal Server Error", "예상치 못한 오류가 발생했습니다."), request);
    }

    // ---------- Content-Type 강제 ----------

    /** Accept와 무관하게 ProblemDetail은 application/problem+json으로 나간다. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail) {
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(headers);
            copy.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return new ResponseEntity<>(body, copy, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // ---------- helpers ----------

    private ResponseEntity<Object> respond(ProblemDetail pd, WebRequest request) {
        if (request instanceof ServletWebRequest swr) {
            pd.setInstance(URI.create(swr.getRequest().getRequestURI()));
        }
        return createResponseEntity(pd, new HttpHeaders(), HttpStatusCode.valueOf(pd.getStatus()), request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        return pd;
    }

    private FieldViolation toViolation(ObjectError error) {
        String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
        return new FieldViolation(field, error.getDefaultMessage());
    }

    private static ProblemDetail validationProblem(List<FieldViolation> violations) {
        List<FieldViolation> sorted = violations.stream()
                .distinct()
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        String fields = sorted.stream().map(FieldViolation::field).distinct().collect(Collectors.joining(", "));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation Failed",
                "요청 값이 유효하지 않습니다: " + fields);
        pd.setProperty("errors", sorted.stream()
                .map(v -> Map.of("field", v.field(), "message", v.message()))
                .toList());
        return pd;
    }

    /** JsonMappingException의 경로를 `items[0].quantity` 형태로 만든다. 모르면 빈 문자열. */
    private static String jsonPath(HttpMessageNotReadableException ex) {
        if (!(ex.getCause() instanceof JsonMappingException jme) || jme.getPath().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : jme.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }
}
