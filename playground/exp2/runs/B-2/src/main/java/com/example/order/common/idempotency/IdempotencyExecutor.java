package com.example.order.common.idempotency;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Idempotency-Key 처리(R4). 키를 선점한 요청만 실제로 처리하고, 2xx 응답만 저장해 재생한다.
 * 처리가 예외로 끝나면 키를 풀어 같은 요청으로 다시 시도할 수 있게 한다.
 */
@Component
public class IdempotencyExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param userId 요청자 식별자(X-User-Id), 없으면 null
     * @param path   요청 경로
     * @param body   검증을 통과한 요청 본문 — 직렬화한 값으로 "같은 요청"인지 판별한다
     */
    public ResponseEntity<?> execute(IdempotencyScope scope, String key, String userId, String path, Object body,
                                     Supplier<ResponseEntity<?>> action) {
        String fingerprint = fingerprint(userId, path, body);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (repository.tryAcquire(scope, key, fingerprint)) {
                return runAndRecord(scope, key, action);
            }
            Optional<IdempotencyRecord> existing = repository.find(scope, key);
            if (existing.isEmpty()) {
                continue; // 앞선 요청이 실패해 키가 방금 풀렸다 — 다시 선점을 시도한다
            }
            IdempotencyRecord record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "같은 Idempotency-Key로 다른 요청이 들어왔습니다: " + key);
            }
            if (record.completed()) {
                return replay(record);
            }
            throw inProgress(key);
        }
        throw inProgress(key);
    }

    private ResponseEntity<?> runAndRecord(IdempotencyScope scope, String key, Supplier<ResponseEntity<?>> action) {
        boolean recorded = false;
        try {
            ResponseEntity<?> response = action.get();
            if (response.getStatusCode().is2xxSuccessful()) {
                URI location = response.getHeaders().getLocation();
                repository.complete(scope, key, response.getStatusCode().value(), toJson(response.getBody()),
                        location == null ? null : location.toString());
                recorded = true;
            }
            return response;
        } finally {
            if (!recorded) {
                repository.release(scope, key);
            }
        }
    }

    private ResponseEntity<?> replay(IdempotencyRecord record) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(record.responseStatus());
        if (record.responseLocation() != null) {
            builder.location(URI.create(record.responseLocation()));
        }
        try {
            return builder.contentType(MediaType.APPLICATION_JSON).body(objectMapper.readTree(record.responseBody()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 멱등 응답을 읽지 못했습니다", e);
        }
    }

    private String fingerprint(String userId, String path, Object body) {
        String canonical = (userId == null ? "" : userId) + "\n" + path + "\n" + toJson(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다", e);
        }
    }

    private static BusinessException inProgress(String key) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "같은 Idempotency-Key의 요청이 처리 중입니다: " + key);
    }
}
