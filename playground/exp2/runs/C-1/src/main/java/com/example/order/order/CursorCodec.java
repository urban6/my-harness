package com.example.order.order;

import com.example.order.common.error.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 커서 = base64url(no padding) of "{createdAt}|{id}". */
public final class CursorCodec {

    public record Cursor(Instant createdAt, long id) {
    }

    private CursorCodec() {
    }

    public static String encode(Instant createdAt, long id) {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) {
                throw new InvalidCursorException("해석할 수 없는 cursor 입니다.");
            }
            return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new InvalidCursorException("해석할 수 없는 cursor 입니다.");
        }
    }
}
