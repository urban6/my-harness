package com.example.order.order;

public class OrderAlreadyCancelledException extends RuntimeException {

    public OrderAlreadyCancelledException(Long id) {
        super("이미 취소된 주문입니다: id=" + id);
    }
}
