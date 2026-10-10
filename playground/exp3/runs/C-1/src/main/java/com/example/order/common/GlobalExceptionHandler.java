package com.example.order.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every error as application/problem+json with the urn:problem:order-payment:{slug} type. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemType type = ex.type();
        ProblemDetail pd = problem(type, type.status(), ex.getMessage(), request.getRequestURI());
        ex.extensions().forEach(pd::setProperty);
        return ResponseEntity.status(type.status()).headers(ex.headers()).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        ProblemDetail pd = problem(ProblemType.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
        ProblemType type = typeFor(ex, statusCode);
        ProblemDetail pd = problem(type, statusCode, detailFor(type), path);
        if (ex instanceof MethodArgumentNotValidException manve) {
            List<FieldErrorDetail> errors = new ArrayList<>();
            manve.getBindingResult().getFieldErrors()
                    .forEach(fe -> errors.add(new FieldErrorDetail(fe.getField(), fe.getDefaultMessage())));
            manve.getBindingResult().getGlobalErrors()
                    .forEach(ge -> errors.add(new FieldErrorDetail(ge.getObjectName(), ge.getDefaultMessage())));
            pd.setProperty("errors", errors);
        } else if (ex instanceof HandlerMethodValidationException hmve) {
            List<FieldErrorDetail> errors = new ArrayList<>();
            hmve.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e ->
                    errors.add(new FieldErrorDetail(r.getMethodParameter().getParameterName(), e.getDefaultMessage()))));
            pd.setProperty("errors", errors);
        } else if (ex instanceof MissingRequestHeaderException mrhe) {
            pd.setProperty("header", mrhe.getHeaderName());
        }
        if (statusCode.is5xxServerError()) {
            log.error("Server error on {}", path, ex);
        }
        return super.handleExceptionInternal(ex, pd, headers, statusCode, request);
    }

    private static ProblemType typeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
            return ProblemType.VALIDATION_FAILED;
        }
        if (ex instanceof MissingRequestHeaderException) {
            return ProblemType.MISSING_HEADER;
        }
        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
            return ProblemType.RESOURCE_NOT_FOUND;
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return ProblemType.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return ProblemType.UNSUPPORTED_MEDIA_TYPE;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return ProblemType.NOT_ACCEPTABLE;
        }
        if (status.is5xxServerError()) {
            return ProblemType.INTERNAL_ERROR;
        }
        return ProblemType.MALFORMED_REQUEST;
    }

    private static String detailFor(ProblemType type) {
        return switch (type) {
            case VALIDATION_FAILED -> "Request validation failed";
            case MISSING_HEADER -> "A required header is missing";
            case RESOURCE_NOT_FOUND -> "No resource is mapped to this path";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not allowed for this path";
            case UNSUPPORTED_MEDIA_TYPE -> "The request Content-Type is not supported";
            case NOT_ACCEPTABLE -> "The requested response media type is not available";
            case INTERNAL_ERROR -> "Unexpected error";
            default -> "The request is malformed (invalid JSON, missing body or a value of the wrong type)";
        };
    }

    private static ProblemDetail problem(ProblemType type, HttpStatusCode status, String detail, String instance) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(type.typeUri()));
        pd.setTitle(type.title());
        if (instance != null && !instance.isEmpty()) {
            try {
                pd.setInstance(URI.create(instance));
            } catch (IllegalArgumentException ignored) {
                // unparsable raw path: leave instance unset rather than failing the error response
            }
        }
        return pd;
    }
}
