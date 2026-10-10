package com.example.order.ordering;

import com.example.order.common.error.RequestValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/** 목록 커서: Base64URL(패딩 없음) of "<createdAt epoch micros>:<id>" (01 설계 9.2) */
public record OrderCursor(long createdAtMicros, long id) {

    private static final Pattern FORMAT = Pattern.compile("^-?\\d{1,19}:\\d{1,19}$");

    public static OrderCursor of(Order order) {
        Instant at = order.getCreatedAt();
        long micros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1000);
        return new OrderCursor(micros, order.getId());
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAtMicros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public Instant createdAt() {
        return Instant.ofEpochSecond(Math.floorDiv(createdAtMicros, 1_000_000L),
                Math.floorMod(createdAtMicros, 1_000_000L) * 1000L);
    }

    public static OrderCursor decode(String raw) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            if (!FORMAT.matcher(text).matches()) {
                throw invalid();
            }
            int sep = text.indexOf(':');
            long micros = Long.parseLong(text.substring(0, sep));
            long id = Long.parseLong(text.substring(sep + 1));
            if (id < 1) {
                throw invalid();
            }
            return new OrderCursor(micros, id);
        } catch (IllegalArgumentException e) { // Base64 디코딩 실패, 숫자 오버플로
            throw invalid();
        }
    }

    private static RequestValidationException invalid() {
        return new RequestValidationException("cursor", "해석할 수 없는 cursor 입니다");
    }
}
