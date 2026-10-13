package com.example.order.order;

public class OrderAlreadyCancelledException extends RuntimeException {

    private final long id;

    public OrderAlreadyCancelledException(long id) {
        super("이미 취소된 주문입니다");
        this.id = id;
    }

    public long getId() {
        return id;
    }
}
