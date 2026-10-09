package com.example.order.order;

public class OrderNotFoundException extends RuntimeException {

    private final long id;

    public OrderNotFoundException(long id) {
        super("주문을 찾을 수 없습니다");
        this.id = id;
    }

    public long getId() {
        return id;
    }
}
