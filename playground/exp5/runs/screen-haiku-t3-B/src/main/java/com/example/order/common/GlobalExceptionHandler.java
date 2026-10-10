package com.example.order.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import java.util.stream.Collectors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error becomes RFC 9457 application/problem+json with type, title, status, detail, code. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", null);
    }

    /** Spring MVC's own exceptions (parse errors, missing params, type mismatch, 404/405/415 ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        String detail = null;
        if (ex instanceof MethodArgumentNotValidException manv) {
            detail = manv.getBindingResult().getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        } else if (body instanceof ProblemDetail pd) {
            detail = pd.getDetail();
        }
        if (detail == null || detail.isBlank()) {
            detail = ex.getMessage() != null ? ex.getMessage() : "Request failed";
        }
        String code = statusCode.value() == 400 ? "VALIDATION_ERROR" : codeFor(statusCode);
        return problem(statusCode, code, detail, headers);
    }

    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus s = HttpStatus.resolve(statusCode.value());
        return s != null ? s.name() : "ERROR";
    }

    private static ResponseEntity<Object> problem(HttpStatusCode status, String code, String detail,
            HttpHeaders headers) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus resolved = HttpStatus.resolve(status.value());
        pd.setTitle(resolved != null ? resolved.getReasonPhrase() : "Error");
        pd.setProperty("code", code);
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, out, status);
    }
}
