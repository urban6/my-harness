package com.example.order.order.dto;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> content, String nextCursor) {
}
