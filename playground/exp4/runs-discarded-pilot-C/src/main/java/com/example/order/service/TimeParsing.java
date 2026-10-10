package com.example.order.service;

import com.example.order.web.error.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/** 오프셋이 포함된 ISO-8601 만 허용. UTC 연도 0001~9999 범위만 (PostgreSQL 범위 방어). 마이크로초 절삭. */
public final class TimeParsing {

    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    private TimeParsing() {
    }

    public static Instant parseOffsetDateTime(String raw, String field) {
        if (raw == null) {
            throw ApiException.validation(field + ": must not be null");
        }
        try {
            Instant i = OffsetDateTime.parse(raw).toInstant().truncatedTo(ChronoUnit.MICROS);
            if (i.isBefore(MIN) || i.isAfter(MAX)) {
                throw ApiException.validation(field + ": year out of supported range");
            }
            return i;
        } catch (DateTimeParseException | ArithmeticException e) {
            throw ApiException.validation(field + ": must be an ISO-8601 date-time with offset");
        }
    }

    public static boolean inSupportedRange(Instant i) {
        return !i.isBefore(MIN) && !i.isAfter(MAX);
    }
}
