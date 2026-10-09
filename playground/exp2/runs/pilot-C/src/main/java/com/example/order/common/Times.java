package com.example.order.common;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** 모든 시각은 UTC + 마이크로초 정밀도로 정규화한다 (TIMESTAMPTZ 정밀도와 일치). */
public final class Times {
    private Times() {
    }

    public static OffsetDateTime now(Clock clock) {
        return normalize(OffsetDateTime.now(clock));
    }

    public static OffsetDateTime normalize(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
