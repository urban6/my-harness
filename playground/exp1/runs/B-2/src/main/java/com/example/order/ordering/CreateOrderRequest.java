package com.example.order.ordering;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotEmpty List<@NotNull @Valid Item> items
) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) Integer quantity
    ) {
    }

    /** 같은 productId가 두 번 이상 나오면 400. 다른 검증과 함께 서비스 진입 전에 걸러진다. */
    @AssertTrue(message = "같은 productId를 중복해서 주문할 수 없습니다.")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        return items.stream()
                .filter(Objects::nonNull)
                .map(Item::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
