package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** 실제 HTTP로 서버를 호출하는 테스트 클라이언트. 헤더는 이름·값을 번갈아 넘긴다. */
public class Api {

    public record Response(int status, JsonNode body, String rawBody, String contentType, String location) {

        public String code() {
            return body == null ? null : body.path("code").asText(null);
        }

        @Override
        public String toString() {
            return status + " " + rawBody;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    public Api(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path, String... headers) {
        return send(builder(path, headers).GET());
    }

    public Response post(String path, String jsonBody, String... headers) {
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        return send(builder(path, headers).header("Content-Type", "application/json").POST(body));
    }

    private HttpRequest.Builder builder(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return b;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body() == null || r.body().isBlank() ? null : MAPPER.readTree(r.body());
            Optional<String> contentType = r.headers().firstValue("Content-Type");
            return new Response(r.statusCode(), body, r.body(), contentType.orElse(null),
                    r.headers().firstValue("Location").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
