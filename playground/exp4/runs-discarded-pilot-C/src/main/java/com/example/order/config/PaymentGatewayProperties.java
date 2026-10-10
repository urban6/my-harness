package com.example.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** payment.gateway.* 설정. PAYMENT_GATEWAY_URL 은 application.yml 의 placeholder 로 매핑된다. */
@ConfigurationProperties(prefix = "payment.gateway")
public record PaymentGatewayProperties(
        @DefaultValue("http://localhost:9090") String url,
        @DefaultValue("PT2S") Duration timeout) {

    public PaymentGatewayProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("payment.gateway.timeout must be positive");
        }
    }
}
