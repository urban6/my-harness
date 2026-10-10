package com.example.order.common.error;

import com.example.order.common.error.RequestValidationException.FieldIssue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 Problem Details. 모든 오류 응답은 application/problem+json + code 필드를 갖는다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 도메인 예외 ----

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex, HttpServletRequest request) {
        List<FieldIssue> errors = ex instanceof RequestValidationException rve ? rve.getErrors() : List.of();
        return problem(ex.getCode().status(), ex.getCode(), ex.getMessage(), HttpHeaders.EMPTY, request.getRequestURI(), errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldIssue> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "요청 값이 올바르지 않습니다",
                HttpHeaders.EMPTY, request.getRequestURI(), errors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String chain = causeMessages(ex);
        String uri = request.getRequestURI();
        if (chain.contains("uk_coupons_code")) {
            return businessProblem(ErrorCode.DUPLICATE_COUPON_CODE, "이미 존재하는 쿠폰 코드입니다", uri);
        }
        if (chain.contains("ux_orders_active_coupon_user")) {
            return businessProblem(ErrorCode.COUPON_NOT_APPLICABLE, "이미 이 쿠폰을 사용 중인 주문이 있습니다", uri);
        }
        if (chain.contains("ck_products_reserved_range")) {
            return businessProblem(ErrorCode.INSUFFICIENT_STOCK, "주문 가능 수량이 부족합니다", uri);
        }
        if (chain.contains("ck_coupons_used_count")) {
            return businessProblem(ErrorCode.COUPON_EXHAUSTED, "쿠폰이 모두 소진되었습니다", uri);
        }
        log.error("Unmapped data integrity violation", ex);
        return internal(uri);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return internal(request.getRequestURI());
    }

    // ---- 프레임워크(MVC) 예외: 모두 code를 붙인다 ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldIssue> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldIssue(fe.getField(), fe.getDefaultMessage()));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new FieldIssue(oe.getObjectName(), oe.getDefaultMessage()));
        }
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "요청 값이 올바르지 않습니다",
                headers, path(request), errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldIssue> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e ->
                errors.add(new FieldIssue(String.valueOf(r.getMethodParameter().getParameterName()), e.getDefaultMessage()))));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "요청 값이 올바르지 않습니다",
                headers, path(request), errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "요청 본문을 해석할 수 없습니다",
                headers, path(request), List.of(new FieldIssue("body", "올바른 JSON 형식과 타입이어야 합니다")));
    }

    /** 415는 R11.3 표 밖이므로 400으로 매핑한다 (01 설계 11.2). */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "지원하지 않는 Content-Type 입니다",
                HttpHeaders.EMPTY, path(request), List.of(new FieldIssue("header:Content-Type", "application/json 이어야 합니다")));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        ErrorCode code = status == null ? ErrorCode.INTERNAL_ERROR : switch (status) {
            case BAD_REQUEST -> ErrorCode.VALIDATION_ERROR;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
            default -> statusCode.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
        };
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Framework error", ex);
            return internal(path(request));
        }
        String detail = body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : code.title();
        List<FieldIssue> errors = code == ErrorCode.VALIDATION_ERROR ? frameworkIssues(ex) : List.of();
        return problem(statusCode, code, detail, headers, path(request), errors);
    }

    /** 프레임워크가 던진 400에도 errors[]를 1건 이상 채운다 (01 설계 11.1). */
    private static List<FieldIssue> frameworkIssues(Exception ex) {
        if (ex instanceof MethodArgumentTypeMismatchException m) {
            String prefix = m.getParameter().hasParameterAnnotation(RequestParam.class) ? "query:" : "";
            return List.of(new FieldIssue(prefix + m.getName(), "값의 형식이 올바르지 않습니다"));
        }
        if (ex instanceof TypeMismatchException t) {
            return List.of(new FieldIssue(String.valueOf(t.getPropertyName()), "값의 형식이 올바르지 않습니다"));
        }
        if (ex instanceof MissingRequestHeaderException h) {
            return List.of(new FieldIssue("header:" + h.getHeaderName(), "필수 헤더가 없습니다"));
        }
        if (ex instanceof MissingServletRequestParameterException q) {
            return List.of(new FieldIssue("query:" + q.getParameterName(), "필수 파라미터가 없습니다"));
        }
        return List.of(new FieldIssue("request", "요청이 올바르지 않습니다"));
    }

    // ---- 조립 ----

    private ResponseEntity<Object> businessProblem(ErrorCode code, String detail, String uri) {
        return problem(code.status(), code, detail, HttpHeaders.EMPTY, uri, List.of());
    }

    private ResponseEntity<Object> internal(String uri) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "서버 내부 오류가 발생했습니다", HttpHeaders.EMPTY, uri, List.of());
    }

    private ResponseEntity<Object> problem(HttpStatusCode status, ErrorCode code, String detail, HttpHeaders headers,
            String uri, List<FieldIssue> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        if (uri != null) {
            try {
                pd.setInstance(URI.create(uri));
            } catch (IllegalArgumentException ignored) {
                // instance는 선택 필드
            }
        }
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, out, status);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    private static String causeMessages(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString();
    }
}
