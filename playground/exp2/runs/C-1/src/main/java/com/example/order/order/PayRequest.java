package com.example.order.order;

import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank String cardToken) {
}
