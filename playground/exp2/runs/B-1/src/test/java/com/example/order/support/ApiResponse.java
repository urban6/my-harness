package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpHeaders;

public record ApiResponse(int status, HttpHeaders headers, JsonNode body, String rawBody) {

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    public String contentType() {
        return header("Content-Type");
    }

    public String code() {
        return body == null ? null : body.path("code").asText(null);
    }

    public long id() {
        return body.get("id").asLong();
    }

    @Override
    public String toString() {
        return status + " " + rawBody;
    }
}
