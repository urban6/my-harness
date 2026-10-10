package com.example.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 컨트롤러 동작을 멱등하게 실행한다. 같은 키·같은 요청(사용자·경로·본문)이면 최초 응답을 그대로 재생하고,
 * 2xx 응답만 저장한다. 응답 본문은 한 번 직렬화한 문자열을 그대로 써서 재생 결과가 바이트 단위로 같다.
 */
@Component
public class IdempotentRequestExecutor {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotentRequestExecutor(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> execute(IdempotencyScope scope, String key, String userId, String path,
                                          Object requestBody, Supplier<ResponseEntity<?>> action) {
        String fingerprint = fingerprint(userId, path, requestBody);
        if (idempotencyService.claim(scope, key, fingerprint) instanceof IdempotencyService.Replay replay) {
            return toResponse(replay.status(), replay.body(), replay.location());
        }

        ResponseEntity<?> response;
        String body;
        try {
            response = action.get();
            body = objectMapper.writeValueAsString(response.getBody());
        } catch (RuntimeException | JsonProcessingException e) {
            idempotencyService.release(scope, key);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }

        int status = response.getStatusCode().value();
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (response.getStatusCode().is2xxSuccessful()) {
            idempotencyService.complete(scope, key, status, body, location);
        } else {
            idempotencyService.release(scope, key);
        }
        return toResponse(status, body, location);
    }

    private static ResponseEntity<String> toResponse(int status, String body, String location) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            builder.header(HttpHeaders.LOCATION, location);
        }
        return builder.body(body);
    }

    private String fingerprint(String userId, String path, Object requestBody) {
        try {
            String source = (userId == null ? "" : userId) + "\n" + path + "\n" + objectMapper.writeValueAsString(requestBody);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("멱등 키 지문을 만들 수 없습니다", e);
        }
    }
}
