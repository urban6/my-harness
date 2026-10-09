package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class InvalidCursorException extends BusinessException {

    public InvalidCursorException(String cursor) {
        super(ErrorCode.VALIDATION_ERROR, "해석할 수 없는 cursor 입니다: " + cursor);
    }
}
