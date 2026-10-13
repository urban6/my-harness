package com.example.order.orders;

import com.example.order.common.error.BusinessConflictException;

public class OrderAlreadyCancelledException extends BusinessConflictException {

    public OrderAlreadyCancelledException(Long id) {
        super("이미 취소된 주문입니다: id=" + id);
    }
}
