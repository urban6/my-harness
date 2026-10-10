package com.example.order.order;

import com.example.order.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/** keyset 커서: base64url(무패딩)("v1|" + createdAtEpochMicros + "|" + id). */
public record OrderCursor(Instant createdAt, long id) {

    private static final Pattern FORMAT = Pattern.compile("^v1\\|(\\d{1,18})\\|(\\d{1,18})$");
    private static final int MAX_LENGTH = 200;

    public String encode() {
        long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L), createdAt.getNano() / 1000);
        String raw = "v1|" + micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        var m = FORMAT.matcher(raw);
        if (!m.matches()) {
            throw invalid();
        }
        long micros = Long.parseLong(m.group(1));
        long id = Long.parseLong(m.group(2));
        Instant createdAt = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1000L);
        return new OrderCursor(createdAt, id);
    }

    private static ApiException invalid() {
        return ApiException.validation("cursor is invalid.");
    }
}
