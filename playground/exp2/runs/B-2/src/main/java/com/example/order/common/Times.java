package com.example.order.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    /** DB(timestamptz) 정밀도에 맞춘 현재 시각 — 저장 전후 값이 달라지지 않게 한다. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
