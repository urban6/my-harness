package com.example.order.common;

/** 요청한 리소스가 없을 때. 404로 매핑된다. */
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
