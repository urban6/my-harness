package com.example.order.idempotency;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class IdempotencyKeyMismatchException extends BusinessException {

    public IdempotencyKeyMismatchException() {
        super(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다.");
    }
}
