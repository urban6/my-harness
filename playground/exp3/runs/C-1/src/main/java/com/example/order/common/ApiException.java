package com.example.order.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/** Business/validation error rendered by GlobalExceptionHandler as an RFC 9457 problem. */
public class ApiException extends RuntimeException {

    private final ProblemType type;
    private final Map<String, Object> extensions = new LinkedHashMap<>();
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(ProblemType type, String detail) {
        super(detail, null, false, false);
        this.type = type;
    }

    public ApiException with(String key, Object value) {
        if (value != null) {
            extensions.put(key, value);
        }
        return this;
    }

    public ApiException header(String name, String value) {
        headers.add(name, value);
        return this;
    }

    public ProblemType type() {
        return type;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public HttpHeaders headers() {
        return headers;
    }
}
