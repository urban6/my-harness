package com.example.order.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderPaymentProperties.class)
public class AppConfig {

    /** All business "now" values come from this clock (tests replace it with a @Primary mutable clock). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
