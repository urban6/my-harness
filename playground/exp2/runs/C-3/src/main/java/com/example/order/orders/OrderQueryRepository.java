package com.example.order.orders;

import java.util.List;

public interface OrderQueryRepository {

    /** created_at DESC, id DESC 정렬 keyset 조회. cursor/userId/status는 null이면 조건 없음. */
    List<Order> findPage(String userId, OrderStatus status, CursorCodec.Cursor cursor, int limit);
}
