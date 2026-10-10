package com.example.order.common.error;

/** Bean Validation 으로 표현하기 어려운 잘못된 입력. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
