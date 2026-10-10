package com.example.order.ordering.dto;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> content, String nextCursor) {
}
