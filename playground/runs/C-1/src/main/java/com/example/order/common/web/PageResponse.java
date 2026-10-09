package com.example.order.common.web;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static <T> PageResponse<T> of(Page<T> result, int page, int size) {
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }
}
