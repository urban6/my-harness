package com.example.order.orders;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order")
public record OrderProperties(Duration paymentTtl, long expirySweepIntervalMs) {

    public OrderProperties {
        if (paymentTtl == null) {
            paymentTtl = Duration.ofMinutes(15);
        }
        if (expirySweepIntervalMs <= 0) {
            expirySweepIntervalMs = 500;
        }
    }
}
