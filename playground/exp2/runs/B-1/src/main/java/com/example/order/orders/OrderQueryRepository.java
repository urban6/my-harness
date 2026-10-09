package com.example.order.orders;

import java.util.List;

public interface OrderQueryRepository {

    /** createdAt desc, id desc 순 키셋 조회. 각 조건은 null 이면 적용하지 않는다. */
    List<Order> findPage(String userId, OrderStatus status, OrderCursor after, int limit);
}
