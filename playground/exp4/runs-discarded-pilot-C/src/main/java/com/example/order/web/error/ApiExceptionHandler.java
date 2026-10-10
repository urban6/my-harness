package com.example.order.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 Problem Details. 모든 오류는 Accept 와 무관하게 application/problem+json.
 * 프레임워크 예외(400 계열)는 VALIDATION_ERROR 로, 계약 밖 오류(404 경로/405/415/406/500)는 code 를 생략한다.
 * 요청 본문/카드 토큰이 로그·detail 에 새지 않도록 파싱 오류 detail 은 고정 문구를 쓴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.code();
        return build(code.status(), code.title(), ex.getMessage(), code, request.getRequestURI(), ex.errors(), null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        ex.getConstraintViolations().forEach(v ->
                errors.add(new ApiException.FieldError(v.getPropertyPath().toString(), v.getMessage())));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.title(), summarize(errors),
                ErrorCode.VALIDATION_ERROR, request.getRequestURI(), errors, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.", null, request.getRequestURI(), List.of(), null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String instance = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (statusCode.value() == 400) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            String detail;
            if (ex instanceof MethodArgumentNotValidException manv) {
                for (FieldError fe : manv.getBindingResult().getFieldErrors()) {
                    errors.add(new ApiException.FieldError(fe.getField(), fe.getDefaultMessage()));
                }
                manv.getBindingResult().getGlobalErrors().forEach(ge ->
                        errors.add(new ApiException.FieldError(ge.getObjectName(), ge.getDefaultMessage())));
                detail = summarize(errors);
            } else {
                // HttpMessageNotReadable / TypeMismatch / MissingRequestHeader ... : 입력값 원문을 detail 에 싣지 않는다.
                detail = "Invalid request: " + ex.getClass().getSimpleName().replaceAll("Exception$", "");
            }
            return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.title(), detail,
                    ErrorCode.VALIDATION_ERROR, instance, errors, headers);
        }
        String title = status != null ? status.getReasonPhrase() : "Error";
        String detail = ex instanceof ErrorResponse er && er.getBody().getDetail() != null
                ? er.getBody().getDetail() : title;
        if (statusCode.is5xxServerError()) {
            log.error("Server error", ex);
            detail = "An unexpected error occurred.";
        }
        return build(statusCode, title, detail, null, instance, List.of(), headers);
    }

    private static String summarize(List<ApiException.FieldError> errors) {
        if (errors.isEmpty()) {
            return "Validation failed";
        }
        StringBuilder sb = new StringBuilder();
        for (ApiException.FieldError e : errors) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.field()).append(": ").append(e.message());
        }
        return sb.toString();
    }

    private static ResponseEntity<Object> build(HttpStatusCode status, String title, String detail, ErrorCode code,
                                                String instance, List<ApiException.FieldError> errors,
                                                @Nullable HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code != null ? code.typeUri() : "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        if (code != null) {
            body.put("code", code.name());
        }
        if (instance != null) {
            body.put("instance", instance);
        }
        if (errors != null && !errors.isEmpty()) {
            List<Map<String, String>> list = new ArrayList<>();
            for (ApiException.FieldError e : errors) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("field", e.field());
                m.put("message", e.message());
                list.add(m);
            }
            body.put("errors", list);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
