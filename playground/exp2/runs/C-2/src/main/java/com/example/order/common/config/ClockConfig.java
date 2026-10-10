package com.example.order.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** 시간 판단(쿠폰 유효기간, 만료)은 DB now()가 아니라 이 Clock 하나로 한다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
