package com.example.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ORDER_PAYMENT_TTL (기본 PT15M). */
@ConfigurationProperties("order.payment")
public record OrderPaymentProperties(Duration ttl) {

    public OrderPaymentProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(15);
        }
    }
}
