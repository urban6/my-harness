package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 실제 HTTP 로 앱을 호출하는 얇은 테스트 헬퍼. 원문 바이트/헤더를 그대로 검사할 수 있다. */
public class HttpTestClient {

    public record Resp(int status, java.net.http.HttpHeaders headers, String body) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("not json: " + body, e);
            }
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String contentType() {
            return header("Content-Type");
        }

        public String code() {
            return json().path("code").asText(null);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public HttpTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Resp get(String path) {
        return get(path, Map.of());
    }

    public Resp get(String path, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    public Resp post(String path, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return send(b.build());
    }

    public Resp post(String path, String jsonBody) {
        return post(path, jsonBody, Map.of());
    }

    public static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Resp send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(r.statusCode(), r.headers(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
