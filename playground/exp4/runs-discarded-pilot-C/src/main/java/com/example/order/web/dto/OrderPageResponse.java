package com.example.order.web.dto;

import java.util.List;

public record OrderPageResponse(List<OrderResponse> content, String nextCursor) {
}
