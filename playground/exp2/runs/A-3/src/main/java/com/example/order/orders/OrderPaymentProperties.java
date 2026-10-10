package com.example.order.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** ORDER_PAYMENT_TTL: 결제 대기 만료 시간. */
@ConfigurationProperties("order.payment")
public record OrderPaymentProperties(@DefaultValue("PT15M") Duration ttl) {
}
