package com.example.order.order;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> content, String nextCursor) {
}
