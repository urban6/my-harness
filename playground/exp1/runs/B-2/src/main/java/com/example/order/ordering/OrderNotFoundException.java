package com.example.order.ordering;

import com.example.order.common.NotFoundException;

public class OrderNotFoundException extends NotFoundException {

    public OrderNotFoundException(Long id) {
        super("주문을 찾을 수 없습니다: id=" + id);
    }
}
