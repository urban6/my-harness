package com.example.order.common.error;

/** 현재 리소스 상태와 충돌하는 요청 (재고 부족, 잘못된 상태 전이, 중복 등). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
