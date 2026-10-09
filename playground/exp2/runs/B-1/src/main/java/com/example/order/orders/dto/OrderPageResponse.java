package com.example.order.orders.dto;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> content, String nextCursor) {
}
