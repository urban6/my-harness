package com.example.order.order;

import com.example.order.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** (createdAt, id) 키셋 커서. 마지막으로 내려준 주문 바로 다음부터 이어 읽으므로 새 주문이 생겨도 밀리지 않는다. */
public record OrderCursor(Instant createdAt, long id) {

    private static final String PREFIX = "v1:";

    public static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }

    public String encode() {
        String raw = PREFIX + ChronoUnit.MICROS.between(Instant.EPOCH, createdAt) + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (!raw.startsWith(PREFIX)) {
                throw new IllegalArgumentException();
            }
            String[] parts = raw.substring(PREFIX.length()).split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            Instant createdAt = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
            return new OrderCursor(createdAt, Long.parseLong(parts[1]));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            throw ApiException.validation("Invalid cursor");
        }
    }
}
