package com.example.order.common.error;

public class IdempotencyInProgressException extends BusinessException {
    public IdempotencyInProgressException(String detail) {
        super(ErrorCode.IDEMPOTENCY_IN_PROGRESS, detail);
    }
}
