package com.example.order.order.dto;

import java.util.List;

import org.springframework.data.domain.Page;

public record OrderPageResponse(List<OrderResponse> content, int page, int size, long totalElements) {

    public static OrderPageResponse from(Page<OrderResponse> page) {
        return new OrderPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
