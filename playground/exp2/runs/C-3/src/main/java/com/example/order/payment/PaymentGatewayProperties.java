package com.example.order.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.gateway")
public record PaymentGatewayProperties(String url, Duration timeout) {

    public PaymentGatewayProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(2);
        }
    }
}
