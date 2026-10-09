package com.example.order.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.gateway")
public record PaymentGatewayProperties(URI url, Duration connectTimeout, Duration readTimeout) {
    public PaymentGatewayProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(2);
    }
}
