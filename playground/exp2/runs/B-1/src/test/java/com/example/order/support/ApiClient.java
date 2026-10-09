package com.example.order.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 실제 HTTP 로 서버를 호출한다(동시성 테스트에서 진짜 요청 경합을 만들기 위해). */
public final class ApiClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ApiResponse get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    /** headers: 이름, 값 순서의 쌍. 값이 null 이면 그 헤더를 보내지 않는다. */
    public ApiResponse post(String path, Object body, String... headers) {
        return postRaw(path, body == null ? null : toJson(body), headers);
    }

    public ApiResponse postRaw(String path, String rawJson, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .POST(rawJson == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(rawJson))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i + 1] != null) {
                builder.header(headers[i], headers[i + 1]);
            }
        }
        return send(builder);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private ApiResponse send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode body = raw == null || raw.isBlank() ? null : JSON.readTree(raw);
            return new ApiResponse(response.statusCode(), response.headers(), body, raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
