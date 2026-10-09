package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 커서: base64url("v1:{createdAtEpochMicros}:{id}") */
public record OrderCursor(long epochMicros, long id) {
    private static final Pattern FORMAT = Pattern.compile("^v1:(-?\\d{1,19}):(\\d{1,19})$");
    // PostgreSQL timestamptz 범위 안전 구간 (0001-01-01 ~ 9999-12-31)
    private static final long MIN_MICROS = -62_135_596_800L * 1_000_000L;
    private static final long MAX_MICROS = 253_402_300_799L * 1_000_000L;

    public static OrderCursor of(OrderEntity o) {
        Instant i = o.getCreatedAt().toInstant();
        return new OrderCursor(i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000, o.getId());
    }

    public String encode() {
        String raw = "v1:" + epochMicros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = FORMAT.matcher(raw);
            if (!m.matches()) {
                throw bad();
            }
            long micros = Long.parseLong(m.group(1));
            long id = Long.parseLong(m.group(2));
            if (id <= 0 || micros < MIN_MICROS || micros > MAX_MICROS) {
                throw bad();
            }
            return new OrderCursor(micros, id);
        } catch (IllegalArgumentException e) { // Base64 / NumberFormatException
            throw bad();
        }
    }

    public OffsetDateTime createdAt() {
        Instant i = Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L),
                Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
        return i.atOffset(ZoneOffset.UTC);
    }

    private static ApiException bad() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "cursor is invalid");
    }
}
