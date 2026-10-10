package com.example.order.ordering.dto;

import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank String cardToken) {
}
