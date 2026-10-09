package com.example.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order")
public record OrderProperties(Duration paymentTtl, long expirySweepDelayMs) {
    public OrderProperties {
        if (paymentTtl == null) paymentTtl = Duration.ofMinutes(15);
        if (expirySweepDelayMs <= 0) expirySweepDelayMs = 200;
    }
}
