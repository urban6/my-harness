package com.example.order.common.error;

/** 요청한 리소스가 존재하지 않을 때. */
public abstract class ResourceNotFoundException extends RuntimeException {

    protected ResourceNotFoundException(String message) {
        super(message);
    }
}
