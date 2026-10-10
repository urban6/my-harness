package com.example.order.common;

import com.example.order.order.OrderPaymentProperties;
import com.example.order.payment.PaymentGatewayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({OrderPaymentProperties.class, PaymentGatewayProperties.class})
public class AppConfig {
}
