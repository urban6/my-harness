package com.example.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * R4 멱등성. 키를 먼저 선점(IN_PROGRESS)하고 처리한 뒤, 2xx 응답만 저장한다.
 * <ul>
 *   <li>같은 키·같은 요청 → 저장된 응답 재생</li>
 *   <li>같은 키·다른 요청 → 422</li>
 *   <li>같은 키가 처리 중 → 409</li>
 *   <li>오류 응답 → 선점 해제(같은 요청으로 재시도 가능)</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final int MAX_ACQUIRE_ATTEMPTS = 3;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param fingerprint 요청 동일성 판정 재료: 사용자·경로·본문
     */
    public ResponseEntity<?> execute(IdempotencyScope scope, String key, RequestFingerprint fingerprint,
                                     Supplier<ResponseEntity<?>> action) {
        String hash = hash(fingerprint);
        Optional<ResponseEntity<?>> replay = acquireOrReplay(scope.name(), key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            repository.release(scope.name(), key);
            throw e;
        }
        if (response.getStatusCode().is2xxSuccessful()) {
            URI location = response.getHeaders().getLocation();
            repository.complete(scope.name(), key, response.getStatusCode().value(),
                    toJson(response.getBody()), location == null ? null : location.toString());
        } else {
            repository.release(scope.name(), key);
        }
        return response;
    }

    private Optional<ResponseEntity<?>> acquireOrReplay(String scope, String key, String hash) {
        for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
            if (repository.tryAcquire(scope, key, hash)) {
                return Optional.empty();
            }
            Optional<IdempotencyRecord> existing = repository.find(scope, key);
            if (existing.isEmpty()) {
                continue; // 앞선 요청이 실패해 선점이 막 해제됨: 다시 선점을 시도한다.
            }
            IdempotencyRecord record = existing.get();
            if (!record.requestHash().equals(hash)) {
                throw new IdempotencyKeyMismatchException();
            }
            if (!record.completed()) {
                throw new IdempotencyInProgressException();
            }
            return Optional.of(replay(record));
        }
        throw new IdempotencyInProgressException();
    }

    private static ResponseEntity<?> replay(IdempotencyRecord record) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(record.responseStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (record.responseLocation() != null) {
            builder.header(HttpHeaders.LOCATION, record.responseLocation());
        }
        return builder.body(record.responseBody());
    }

    private String hash(RequestFingerprint fingerprint) {
        String material = String.join("\n",
                String.valueOf(fingerprint.userId()), fingerprint.path(), toJson(fingerprint.body()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize idempotent payload", e);
        }
    }
}
