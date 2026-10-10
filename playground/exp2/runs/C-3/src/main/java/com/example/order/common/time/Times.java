package com.example.order.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    /** PostgreSQL timestamptz 정밀도(마이크로초)에 맞춰 절사한 현재 시각. */
    public static Instant now(Clock clock) {
        return truncate(clock.instant());
    }

    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
