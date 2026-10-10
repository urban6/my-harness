package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 실제 HTTP로 API를 호출하는 테스트 클라이언트. */
public class Api {

    public record Resp(int status, java.net.http.HttpHeaders headers, String raw, JsonNode json) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String code() {
            return json.path("code").asText(null);
        }

        public long id() {
            return json.path("id").asLong();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Api(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Resp get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    /** headers는 이름, 값 순서의 쌍. 값이 null인 헤더는 보내지 않는다. */
    public Resp post(String path, Object body, String... headers) {
        try {
            return postRaw(path, body == null ? null : JSON.writeValueAsString(body), headers);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Resp postRaw(String path, String rawBody, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(rawBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(rawBody));
        if (rawBody != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i + 1] != null) {
                request.header(headers[i], headers[i + 1]);
            }
        }
        return send(request);
    }

    private Resp send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = raw == null || raw.isBlank() ? JSON.nullNode() : JSON.readTree(raw);
            return new Resp(response.statusCode(), response.headers(), raw, json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
