package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
        String couponCode) {

    public record Item(@NotNull Long productId, @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    /** Bean Validation 이후의 교차 검증 (productId 중복 불가). 위반 시 400. */
    public void validateCross() {
        Set<Long> seen = new HashSet<>();
        for (Item item : items) {
            if (!seen.add(item.productId())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "duplicate productId " + item.productId());
            }
        }
    }
}
