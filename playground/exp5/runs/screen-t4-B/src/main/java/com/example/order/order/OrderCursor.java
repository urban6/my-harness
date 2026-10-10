package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/** Opaque keyset cursor: base64url("{createdAt epoch micros}:{id}") of the last row of the previous page. */
public record OrderCursor(OffsetDateTime createdAt, long id) {

    public String encode() {
        String raw = Times.toEpochMicros(createdAt) + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep != raw.lastIndexOf(':')) {
                throw new IllegalArgumentException("malformed cursor");
            }
            long micros = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new OrderCursor(Times.fromEpochMicros(micros), id);
        } catch (RuntimeException e) {
            throw ApiException.validation("cursor is invalid");
        }
    }
}
