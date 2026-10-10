package com.example.order.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(PaymentGateway paymentGateway, Order order) {

    public record PaymentGateway(String url) {
    }

    public record Order(Duration paymentTtl) {
    }
}
