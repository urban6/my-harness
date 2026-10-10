package com.example.order.orders;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** base64url(무패딩)( "{epochMicros}:{id}" ) */
public final class CursorCodec {

    // PostgreSQL timestamptz 범위 안전 마진: 0001-01-01 ~ 9999-12-31
    private static final long MIN_MICROS = -62_135_596_800L * 1_000_000L;
    private static final long MAX_MICROS = 253_402_300_799L * 1_000_000L;

    public record Cursor(Instant createdAt, long id) {
    }

    private CursorCodec() {
    }

    public static String encode(Order order) {
        Instant t = order.getCreatedAt();
        long micros = t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000;
        String raw = micros + ":" + order.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            long micros = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0 || micros < MIN_MICROS || micros > MAX_MICROS) {
                throw invalid();
            }
            return new Cursor(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L),
                    id);
        } catch (IllegalArgumentException e) { // base64 오류, NumberFormatException 포함
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor를 해석할 수 없습니다.");
    }
}
