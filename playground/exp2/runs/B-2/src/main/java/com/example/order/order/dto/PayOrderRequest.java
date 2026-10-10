package com.example.order.order.dto;

import jakarta.validation.constraints.NotBlank;

public record PayOrderRequest(@NotBlank String cardToken) {

    // cardToken은 민감 정보 — 로그 등에 노출되지 않게 한다.
    @Override
    public String toString() {
        return "PayOrderRequest[cardToken=***]";
    }
}
