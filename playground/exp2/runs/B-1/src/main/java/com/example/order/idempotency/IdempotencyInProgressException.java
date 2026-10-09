package com.example.order.idempotency;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class IdempotencyInProgressException extends BusinessException {

    public IdempotencyInProgressException() {
        super(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 Idempotency-Key 의 요청이 처리 중입니다.");
    }
}
