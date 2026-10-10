package com.example.order.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PAYMENT_GATEWAY_URL, 전체 데드라인(기본 PT2S). */
@ConfigurationProperties("payment.gateway")
public record PaymentGatewayProperties(String url, Duration timeout) {

    public PaymentGatewayProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:9090";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(2);
        }
    }
}
