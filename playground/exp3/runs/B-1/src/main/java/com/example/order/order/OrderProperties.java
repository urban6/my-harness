package com.example.order.order;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration paymentTtl,
        @DefaultValue("PT1M") Duration gatewayLockTimeout,
        @DefaultValue("PT1S") Duration expirySweepInterval
) {}
