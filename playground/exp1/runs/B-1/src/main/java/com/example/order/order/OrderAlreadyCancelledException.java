package com.example.order.order;

import com.example.order.common.error.ConflictException;

public class OrderAlreadyCancelledException extends ConflictException {

    public OrderAlreadyCancelledException(Long id) {
        super("이미 취소된 주문입니다: id=" + id);
    }
}
