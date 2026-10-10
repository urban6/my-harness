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
import java.util.Set;

public record OrderCreateRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
        String couponCode) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    @JsonIgnore
    @AssertTrue(message = "items must not contain duplicate productId")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        for (Item item : items) {
            if (item != null && item.productId() != null && !seen.add(item.productId())) {
                return false;
            }
        }
        return true;
    }

    @JsonIgnore
    @AssertTrue(message = "couponCode must not be blank")
    public boolean isCouponCodeNotBlank() {
        return couponCode == null || !couponCode.isBlank();
    }

    /** 멱등 지문용 정규 본문: 검증을 통과한 DTO 에서 만든다. */
    public String normalized() {
        StringBuilder sb = new StringBuilder("items=[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(items.get(i).productId()).append(':').append(items.get(i).quantity());
        }
        sb.append("];coupon=").append(couponCode == null ? "∅" : couponCode);
        return sb.toString();
    }
}
