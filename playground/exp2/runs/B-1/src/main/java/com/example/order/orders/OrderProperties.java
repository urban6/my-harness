package com.example.order.orders;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param paymentTtl 결제 대기 만료 시간(ORDER_PAYMENT_TTL)
 */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(@DefaultValue("PT15M") Duration paymentTtl) {
}
