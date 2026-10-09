package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class InvalidOrderStateException extends BusinessException {

    public InvalidOrderStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }
}
