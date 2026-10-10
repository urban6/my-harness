package com.example.order.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 앱의 Clock 을 {@link MutableClock} 으로 대체한다(@Primary). 이 설정을 쓰는 클래스는 만료 스케줄러를 끄고 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class ClockTestConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.now());
    }
}
