package com.example.order.common.error;

/** 리소스의 현재 상태 때문에 요청을 처리할 수 없을 때. */
public abstract class BusinessConflictException extends RuntimeException {

    protected BusinessConflictException(String message) {
        super(message);
    }
}
