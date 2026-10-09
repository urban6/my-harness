package com.example.order.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 서버가 만드는 모든 시각의 단일 출처. Postgres timestamptz 정밀도에 맞춰 마이크로초로 절삭한다. */
@Component
public class TimeProvider {

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
