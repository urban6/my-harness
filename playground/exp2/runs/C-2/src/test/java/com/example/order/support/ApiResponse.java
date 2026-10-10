package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;

/** HTTP 응답 스냅샷. json은 본문이 JSON이 아니면 null. */
public record ApiResponse(int status, HttpHeaders headers, String body, JsonNode json) {

    public String header(String name) {
        return headers.getFirst(name);
    }

    public String contentType() {
        return header(HttpHeaders.CONTENT_TYPE);
    }

    /** 최상위 필드의 텍스트 값 (없거나 null이면 null). */
    public String text(String field) {
        JsonNode n = json == null ? null : json.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public long longValue(String field) {
        return json.get(field).asLong();
    }

    /** 응답 본문의 id (상품/주문). */
    public long id() {
        return longValue("id");
    }

    /** Problem Details의 code. */
    public String code() {
        return text("code");
    }

    @Override
    public String toString() {
        return status + " " + body;
    }
}
