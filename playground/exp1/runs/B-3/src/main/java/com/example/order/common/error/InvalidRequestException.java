package com.example.order.common.error;

/** Bean Validation으로 표현하기 어려운 요청 규칙 위반. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
