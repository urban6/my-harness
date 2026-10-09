package com.example.order.order.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CreateOrderRequest(
        @NotEmpty List<@NotNull @Valid OrderItemRequest> items
) {

    @JsonIgnore
    @AssertTrue(message = "같은 productId 를 중복해서 주문할 수 없습니다")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        return items.stream()
                .filter(Objects::nonNull)
                .map(OrderItemRequest::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
