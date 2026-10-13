package com.example.order.order.dto;

import com.example.order.order.UniqueProductIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "items는 1개 이상이어야 합니다.")
        @UniqueProductIds(message = "items에 같은 productId가 중복될 수 없습니다.")
        List<@NotNull(message = "items의 원소는 null일 수 없습니다.") @Valid OrderItemRequest> items) {}
