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

    public HttpStatus getStatus() { return status; }
    public String getTitle() { return title; }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not Found", what + " not found: " + id);
    }

    public static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }

    public static ApiException unprocessable(String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, title, detail);
    }

    public static ApiException badGateway(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "Payment Gateway Error", detail);
    }
}
