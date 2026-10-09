package com.example.order.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payment.gateway")
public record PaymentGatewayProperties(
        String url,
        @DefaultValue("PT2S") Duration timeout
) {
}
