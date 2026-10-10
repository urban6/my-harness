package com.example.order.common.error;

public class IdempotencyKeyMismatchException extends BusinessException {
    public IdempotencyKeyMismatchException(String detail) {
        super(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, detail);
    }
}
