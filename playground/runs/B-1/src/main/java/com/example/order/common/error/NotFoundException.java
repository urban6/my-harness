package com.example.order.common.error;

/** 요청한 리소스가 존재하지 않음. 전역 핸들러에서 404로 매핑된다. */
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
