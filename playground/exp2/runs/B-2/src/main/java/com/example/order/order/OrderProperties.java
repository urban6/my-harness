package com.example.order.order;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param paymentTtl         결제 대기 만료 시간 (ORDER_PAYMENT_TTL)
 * @param expirationInterval 만료 주문을 정리하는 주기
 */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTtl, Duration expirationInterval) {
}
