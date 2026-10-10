package com.example.order.common.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.gateway")
public record PaymentGatewayProperties(
        @DefaultValue("http://localhost:9090") URI url,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT2S") Duration readTimeout) {
}
