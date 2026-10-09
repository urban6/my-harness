package com.example.order.common;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** DB(timestamptz) 정밀도와 응답 형식(오프셋 포함 ISO-8601)을 한곳에서 맞춘다. */
public final class Times {

    private Times() {
    }

    public static Instant now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant toInstant(OffsetDateTime value) {
        return value.toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime toOffset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
