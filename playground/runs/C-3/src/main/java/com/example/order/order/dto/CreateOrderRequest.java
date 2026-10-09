package com.example.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CreateOrderRequest(
        @NotEmpty(message = "1개 이상이어야 합니다")
        List<@NotNull(message = "항목은 null일 수 없습니다") @Valid OrderItemRequest> items) {

    /** 같은 productId 중복 불가. 다른 제약이 잡을 수 있는 경우(null 등)는 true로 넘긴다. */
    @JsonIgnore
    @AssertTrue(message = "같은 productId를 중복해서 주문할 수 없습니다")
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
