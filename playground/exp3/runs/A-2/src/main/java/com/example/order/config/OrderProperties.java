package com.example.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTtl, Duration expirySweepInterval) {
}
