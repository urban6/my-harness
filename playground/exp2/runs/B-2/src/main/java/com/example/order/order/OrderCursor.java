package com.example.order.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;

/** 목록 키셋 커서: 마지막으로 내려준 주문의 (createdAt, id). */
record OrderCursor(Instant createdAt, long id) {

    static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }

    String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static OrderCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new OrderCursor(Instant.parse(raw.substring(0, separator)), Long.parseLong(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor를 해석할 수 없습니다: " + cursor);
        }
    }
}
