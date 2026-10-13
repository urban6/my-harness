package com.example.order.orders;

import com.example.order.common.error.ResourceNotFoundException;

public class OrderNotFoundException extends ResourceNotFoundException {

    public OrderNotFoundException(Long id) {
        super("주문을 찾을 수 없습니다: id=" + id);
    }
}
