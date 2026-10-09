package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

public class OrderNotFoundException extends BusinessException {

    public OrderNotFoundException(Long id) {
        super(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: id=" + id);
    }
}
