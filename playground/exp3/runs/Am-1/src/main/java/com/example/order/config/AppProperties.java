package com.example.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String paymentGatewayUrl,
        Duration orderPaymentTtl,
        Duration paymentLockTimeout,
        Duration expirationScanInterval) {
}
