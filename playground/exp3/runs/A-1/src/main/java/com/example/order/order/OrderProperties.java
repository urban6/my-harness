package com.example.order.order;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTtl, Duration expirationSweepInterval) {

    @Configuration
    @EnableConfigurationProperties(OrderProperties.class)
    static class Registration {
    }
}
