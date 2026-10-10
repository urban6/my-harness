package com.example.order.config;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 앱이 만드는 모든 Instant 는 마이크로초로 절삭한다 (PostgreSQL TIMESTAMPTZ 정밀도, 01 문서 0.2절). */
@Component
public class AppClock {

    private final Clock clock;

    public AppClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
