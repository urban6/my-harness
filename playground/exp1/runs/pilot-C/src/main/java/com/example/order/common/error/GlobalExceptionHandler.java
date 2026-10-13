package com.example.order.common.error;

import com.example.order.product.ProductNotFoundException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 에러를 RFC 9457 application/problem+json 으로 매핑한다 (01_api_design.md §4).
 * Accept 협상에 의존하지 않도록 Content-Type 을 명시 지정한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String DETAIL_VALIDATION = "요청 본문 검증에 실패했습니다.";
    private static final String DETAIL_MALFORMED_GENERIC = "요청 본문을 읽을 수 없습니다. 올바른 JSON 형식인지 확인하세요.";
    private static final String DETAIL_UNEXPECTED = "예상치 못한 오류가 발생했습니다.";

    private static final Comparator<Map<String, String>> ERROR_ORDER =
            Comparator.<Map<String, String>, String>comparing(e -> e.get("field"))
                    .thenComparing(e -> e.get("message"));

    /** Spring MVC 표준 예외를 포함한 모든 ProblemDetail 응답의 Content-Type 을 problem+json 으로 고정한다. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(headers);
        if (body instanceof ProblemDetail) {
            merged.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return new ResponseEntity<>(body, merged, statusCode);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::toError)
                .sorted(ERROR_ORDER)
                .toList();

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                ProblemTypes.TITLE_VALIDATION_FAILED, DETAIL_VALIDATION, request);
        pd.setProperty("errors", errors);
        return createResponseEntity(pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = extractFieldName(ex);
        String detail = field != null
                ? "필드 '" + field + "'의 값 형식이 올바르지 않습니다."
                : DETAIL_MALFORMED_GENERIC;
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST_BODY,
                ProblemTypes.TITLE_MALFORMED_REQUEST_BODY, detail, request);
        return createResponseEntity(pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "";
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PATH_PARAMETER,
                ProblemTypes.TITLE_INVALID_PATH_PARAMETER, "경로 변수 '" + name + "'는 정수여야 합니다.", request);
        return createResponseEntity(pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Object> handleProductNotFound(ProductNotFoundException ex, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.NOT_FOUND, ProblemTypes.PRODUCT_NOT_FOUND,
                ProblemTypes.TITLE_PRODUCT_NOT_FOUND, ex.getMessage(), request);
        return createResponseEntity(pd, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, URI.create("about:blank"),
                ProblemTypes.TITLE_INTERNAL_SERVER_ERROR, DETAIL_UNEXPECTED, request);
        return createResponseEntity(pd, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private static ProblemDetail problem(
            HttpStatus status, URI type, String title, String detail, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        if (request instanceof ServletWebRequest swr) {
            pd.setInstance(URI.create(swr.getRequest().getRequestURI()));
        }
        return pd;
    }

    private static Map<String, String> toError(ObjectError error) {
        String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
        String message = error.getDefaultMessage() != null ? error.getDefaultMessage() : "";
        return Map.of("field", field, "message", message);
    }

    @Nullable
    private static String extractFieldName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
                String name = jme.getPath().get(jme.getPath().size() - 1).getFieldName();
                if (name != null) {
                    return name;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
