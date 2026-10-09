package com.example.order.orders.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderItemRequest> items,
        String couponCode
) {

    @JsonIgnore
    @AssertTrue(message = "같은 productId 를 중복해서 담을 수 없습니다")
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
