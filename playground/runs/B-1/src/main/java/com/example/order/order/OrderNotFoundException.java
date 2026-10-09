package com.example.order.order;

import com.example.order.common.error.NotFoundException;

public class OrderNotFoundException extends NotFoundException {

    public OrderNotFoundException(Long id) {
        super("주문을 찾을 수 없습니다: id=" + id);
    }
}
