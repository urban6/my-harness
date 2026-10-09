package com.example.order.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** PostgreSQL timestamptz has microsecond precision; keep every timestamp at that precision in UTC. */
public final class Times {

    private Times() {
    }

    public static OffsetDateTime now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    public static OffsetDateTime normalize(OffsetDateTime t) {
        return t.toInstant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    public static long toEpochMicros(OffsetDateTime t) {
        Instant i = t.toInstant();
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L);
    }

    public static OffsetDateTime fromEpochMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
