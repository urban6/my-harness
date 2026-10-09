package com.example.order.order;

import com.example.order.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** 목록의 마지막 원소 위치 (createdAt, id). 키셋 페이지네이션에 쓴다 (R9.4, R9.5). */
record OrderCursor(Instant createdAt, long id) {

    String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static OrderCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("malformed cursor");
            }
            long micros = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            return new OrderCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException e) {
            throw ApiException.validation("cursor is invalid");
        }
    }
}
