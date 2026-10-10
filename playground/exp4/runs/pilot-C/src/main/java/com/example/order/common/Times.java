package com.example.order.common;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    /** 현재 시각을 마이크로초로 절단한다 (PG TIMESTAMPTZ 정밀도와 일치). */
    public static Instant now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
