package com.example.order.payment;

import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank String cardToken) {
}
