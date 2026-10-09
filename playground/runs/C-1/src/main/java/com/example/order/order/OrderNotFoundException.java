package com.example.order.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("주문을 찾을 수 없습니다: id=" + id);
    }
}
