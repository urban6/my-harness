package com.example.order.payment;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.gateway")
public record PaymentProperties(
        @NotBlank String url,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout
) {}
