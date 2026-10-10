package com.example.order.order;

import java.util.List;

public record OrderPage(List<OrderResponse> content, String nextCursor) {
}
