package com.example.order.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration paymentTtl,
        @DefaultValue Expiration expiration) {

    public OrderProperties {
        if (paymentTtl == null || paymentTtl.isZero() || paymentTtl.isNegative()) {
            throw new IllegalArgumentException("order.payment-ttl must be positive: " + paymentTtl);
        }
    }

    public record Expiration(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") long scanIntervalMs,
            @DefaultValue("100") int batchSize) {
    }
}
