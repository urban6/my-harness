package com.example.order.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("payment.gateway")
public record PaymentGatewayProperties(@DefaultValue("http://localhost:9090") String url,
                                       @DefaultValue("PT2S") Duration timeout) {
}
