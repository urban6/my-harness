package com.example.order.order;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 목록 키셋 페이지네이션 커서: (createdAt, id) 이후 위치를 가리킨다. */
public record OrderCursor(Instant createdAt, long id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new OrderCursor(Instant.parse(raw.substring(0, separator)), Long.parseLong(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 값을 해석할 수 없습니다");
        }
    }

    public static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }
}
