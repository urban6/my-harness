package com.example.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param paymentTtl 결제 대기 만료 시간 (ORDER_PAYMENT_TTL) */
@ConfigurationProperties("order")
public record OrderProperties(Duration paymentTtl) {

    public OrderProperties {
        if (paymentTtl == null) {
            paymentTtl = Duration.ofMinutes(15);
        }
    }
}
