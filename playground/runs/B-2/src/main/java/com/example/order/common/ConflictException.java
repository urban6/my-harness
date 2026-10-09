package com.example.order.common;

/** 리소스의 현재 상태와 요청이 충돌할 때. 409로 매핑된다. */
public abstract class ConflictException extends RuntimeException {

    protected ConflictException(String message) {
        super(message);
    }
}
