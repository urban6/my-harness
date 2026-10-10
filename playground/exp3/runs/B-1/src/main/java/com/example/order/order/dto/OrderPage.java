package com.example.order.order.dto;

import java.util.List;

public record OrderPage(List<OrderResponse> content, String nextCursor) {}
