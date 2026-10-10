package com.example.order.orders.dto;

import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank String cardToken) {
}
