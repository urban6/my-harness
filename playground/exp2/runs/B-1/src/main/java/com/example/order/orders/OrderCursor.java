package com.example.order.orders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 키셋 커서: 마지막으로 내려간 주문의 (createdAt, id). 외부에는 불투명한 base64url 문자열로 노출한다. */
public record OrderCursor(Instant createdAt, long id) {

    private static final String SEPARATOR = "|";

    public static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }

    public String encode() {
        String raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new InvalidCursorException(encoded);
            }
            return new OrderCursor(Instant.parse(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new InvalidCursorException(encoded);
        }
    }
}
