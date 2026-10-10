package com.example.order.order.dto;

import jakarta.validation.constraints.NotBlank;

public record PayRequest(@NotBlank String cardToken) {}
