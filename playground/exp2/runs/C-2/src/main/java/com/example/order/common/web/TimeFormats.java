package com.example.order.common.web;

import com.example.order.common.error.RequestValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class TimeFormats {

    private TimeFormats() {
    }

    /** 응답용: 같은 시점을 UTC(Z)로 정규화한다. null 안전. */
    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 오프셋이 반드시 있는 ISO-8601 문자열을 µs 정밀도의 Instant로 변환. 실패하면 400. */
    public static Instant parseOffsetDateTime(String field, String value) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new RequestValidationException(field, "오프셋이 포함된 ISO-8601 시각이어야 합니다");
        }
    }
}
