package com.example.order.product;

public record Product(long id, String name, long price, long stock, long reserved) {

    public long available() {
        return stock - reserved;
    }
}
