package com.example.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order")
public record OrderProperties(Duration paymentTtl, long expirySweepDelayMs) {
}
