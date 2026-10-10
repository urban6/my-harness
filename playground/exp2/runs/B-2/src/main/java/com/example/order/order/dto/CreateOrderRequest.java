package com.example.order.order.dto;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderItemRequest> items,
        String couponCode
) {

    @JsonIgnore
    @AssertTrue(message = "같은 productId를 중복해서 담을 수 없습니다")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        List<Long> ids = items.stream().filter(Objects::nonNull).map(OrderItemRequest::productId).toList();
        return ids.stream().distinct().count() == ids.size();
    }
}
