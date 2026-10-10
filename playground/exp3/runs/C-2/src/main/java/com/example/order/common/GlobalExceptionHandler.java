package com.example.order.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.beans.TypeMismatchException;

/** RFC 9457 Problem Details for every error (type = https://example.com/problems/{slug}, extension "code"). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://example.com/problems/";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getSlug(), ex.getTitle(), ex.getMessage(), request.getRequestURI());
        ex.getExtensions().forEach(pd::setProperty);
        return respond(pd, new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred.", request.getRequestURI());
        return respond(pd, new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new java.util.ArrayList<>();
        ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(fe -> fe.getField()))
                .forEach(fe -> errors.add(new FieldViolation(fe.getField(), fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new FieldViolation(ge.getObjectName(), ge.getDefaultMessage())));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed.", path(request));
        pd.setProperty("errors", errors);
        return respond(pd, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // fixed text: never echo the request body (it may contain cardToken)
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request body is missing, not valid JSON, or has a field of the wrong type.", path(request));
        return respond(pd, headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "unknown" : ex.getPropertyName();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid parameter",
                "Parameter '" + name + "' has an invalid value.", path(request));
        pd.setProperty("parameter", name);
        return respond(pd, headers);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException m) {
            ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "missing-required-header", "Missing required header",
                    "Required header '" + m.getHeaderName() + "' is missing.", path(request));
            pd.setProperty("header", m.getHeaderName());
            return respond(pd, headers);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** Remaining framework errors (405, 415, 404 ...) get the same shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> base = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        ProblemDetail pd = base.getBody() instanceof ProblemDetail p ? p : ProblemDetail.forStatus(statusCode);
        if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
            String slug = slugFor(ex, statusCode);
            pd.setType(URI.create(TYPE_BASE + slug));
            if (pd.getTitle() == null) {
                pd.setTitle(titleFor(statusCode));
            }
            if (statusCode.is5xxServerError()) {
                pd.setDetail("An unexpected error occurred.");
            }
            pd.setInstance(toUri(path(request)));
            pd.setProperty("code", codeOf(slug));
        }
        HttpHeaders out = new HttpHeaders();
        out.putAll(base.getHeaders());
        return respond(pd, out);
    }

    private static String slugFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return "method-not-allowed";
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return "unsupported-media-type";
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return "not-acceptable";
        }
        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException || status.value() == 404) {
            return "resource-not-found";
        }
        if (status.is5xxServerError()) {
            return "internal-error";
        }
        return "malformed-request";
    }

    private static String titleFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail, String path) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug));
        pd.setTitle(title);
        pd.setInstance(toUri(path));
        pd.setProperty("code", codeOf(slug));
        return pd;
    }

    private static ResponseEntity<Object> respond(ProblemDetail pd, HttpHeaders headers) {
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, out, HttpStatusCode.valueOf(pd.getStatus()));
    }

    private static String codeOf(String slug) {
        return slug.toUpperCase().replace('-', '_');
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "/";
    }

    private static URI toUri(String path) {
        try {
            return URI.create(path);
        } catch (IllegalArgumentException e) {
            return URI.create("/");
        }
    }
}
