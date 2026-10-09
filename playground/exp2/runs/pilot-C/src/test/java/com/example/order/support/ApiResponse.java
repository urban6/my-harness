package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpHeaders;

/** 테스트용 HTTP 응답. body 는 JSON 이면 JsonNode, 비어 있으면 null. */
public record ApiResponse(int status, HttpHeaders headers, String rawBody, JsonNode body) {

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    public String contentType() {
        return header("Content-Type");
    }

    public boolean isProblemJson() {
        return contentType() != null && contentType().toLowerCase().startsWith("application/problem+json");
    }

    public String code() {
        return body == null || !body.hasNonNull("code") ? null : body.get("code").asText();
    }

    public long id() {
        return body.get("id").asLong();
    }

    public JsonNode json(String field) {
        return body.get(field);
    }
}
