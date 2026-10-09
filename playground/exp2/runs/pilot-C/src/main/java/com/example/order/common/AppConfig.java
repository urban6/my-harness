package com.example.order.common;

import com.example.order.order.OrderProperties;
import com.example.order.payment.PaymentGatewayProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({PaymentGatewayProperties.class, OrderProperties.class})
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
