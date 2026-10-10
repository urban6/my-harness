package com.example.order.service;

import com.example.order.web.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 커서 = base64url(no padding)("v1:" + createdAtEpochMicros + ":" + id). 01 문서 9절. */
public final class CursorCodec {

    public record Cursor(Instant createdAt, long id) {
    }

    private static final Pattern ALPHABET = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern BODY = Pattern.compile("v1:(\\d+):(\\d+)");

    private CursorCodec() {
    }

    public static String encode(Instant createdAt, long id) {
        long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L), createdAt.getNano() / 1000);
        String raw = "v1:" + micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || !ALPHABET.matcher(cursor).matches()) {
            throw invalid();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = BODY.matcher(raw);
            if (!m.matches()) {
                throw invalid();
            }
            long micros = Long.parseLong(m.group(1));
            long id = Long.parseLong(m.group(2));
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
            if (!TimeParsing.inSupportedRange(at)) {
                throw invalid();
            }
            return new Cursor(at, id);
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.validation("cursor: invalid cursor");
    }
}
