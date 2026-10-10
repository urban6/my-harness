package com.example.order.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business/API error rendered as an RFC 9457 problem (slug -> type URI + code). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final String title;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public ApiException with(String name, Object value) {
        extensions.put(name, value);
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
