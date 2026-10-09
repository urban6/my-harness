package com.example.order.order;

import com.example.order.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 목록 키셋 커서: 마지막 원소의 (createdAt, id). */
record OrderCursor(Instant createdAt, long id) {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }

    String encode() {
        long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), MICROS_PER_SECOND),
                createdAt.getNano() / 1_000L);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static OrderCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("malformed cursor");
            }
            long micros = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            Instant createdAt = Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                    Math.floorMod(micros, MICROS_PER_SECOND) * 1_000L);
            return new OrderCursor(createdAt, id);
        } catch (RuntimeException e) {
            throw ApiException.validation("cursor: cannot be parsed");
        }
    }
}
