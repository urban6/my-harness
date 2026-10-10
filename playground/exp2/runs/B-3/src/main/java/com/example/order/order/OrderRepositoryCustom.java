package com.example.order.order;

import java.util.List;

public interface OrderRepositoryCustom {

    /** createdAt 내림차순, id 내림차순으로 cursor 이후의 주문을 최대 limit개 조회한다. 필터는 null이면 무시한다. */
    List<Order> findPage(String userId, OrderStatus status, OrderCursor after, int limit);
}
