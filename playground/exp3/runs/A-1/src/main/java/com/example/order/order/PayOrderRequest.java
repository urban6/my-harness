package com.example.order.order;

import jakarta.validation.constraints.NotBlank;

public record PayOrderRequest(@NotBlank String cardToken) {
}
