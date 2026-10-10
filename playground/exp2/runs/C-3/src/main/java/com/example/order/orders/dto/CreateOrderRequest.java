package com.example.order.orders.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderItemRequest> items,
        String couponCode) {

    @JsonIgnore
    @AssertTrue(message = "items에 같은 productId가 중복될 수 없습니다")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (item == null || item.productId() == null) {
                continue;
            }
            if (!seen.add(item.productId())) {
                return false;
            }
        }
        return true;
    }
}
