package com.example.order.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayRequest(@NotBlank @Size(max = 255) String cardToken) {
}
