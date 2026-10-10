package com.example.order.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class TimeSupport {

    private TimeSupport() {
    }

    /** Current time truncated to microseconds (TIMESTAMPTZ precision). */
    public static Instant now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
