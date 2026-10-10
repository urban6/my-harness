package com.example.order.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not Found", what + " " + id + " not found");
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad Request", detail);
    }

    public HttpStatus getStatus() { return status; }
    public String getTitle() { return title; }
}
