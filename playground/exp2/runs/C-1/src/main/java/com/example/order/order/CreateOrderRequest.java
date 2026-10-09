package com.example.order.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
        String couponCode) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    @JsonIgnore
    @AssertTrue
    public boolean hasNoDuplicateProduct() {
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
