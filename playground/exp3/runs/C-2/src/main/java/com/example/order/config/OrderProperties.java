package com.example.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration paymentTtl,
        @DefaultValue("true") boolean expirySweepEnabled,
        @DefaultValue("PT10S") Duration expirySweepInterval) {

    public OrderProperties {
        if (paymentTtl == null || paymentTtl.isZero() || paymentTtl.isNegative()) {
            throw new IllegalArgumentException("order.payment-ttl (ORDER_PAYMENT_TTL) must be a positive ISO-8601 duration");
        }
        if (expirySweepInterval == null || expirySweepInterval.isZero() || expirySweepInterval.isNegative()) {
            throw new IllegalArgumentException("order.expiry-sweep-interval must be a positive ISO-8601 duration");
        }
    }
}
