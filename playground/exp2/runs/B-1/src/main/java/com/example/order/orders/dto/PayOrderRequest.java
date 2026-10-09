package com.example.order.orders.dto;

import jakarta.validation.constraints.NotBlank;

public record PayOrderRequest(@NotBlank String cardToken) {

    @Override
    public String toString() {
        return "PayOrderRequest[cardToken=***]";
    }
}
