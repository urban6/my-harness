package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Idempotency-Key 처리 (R4).
 * <p>
 * 키를 IN_PROGRESS 로 먼저 선점(INSERT … ON CONFLICT DO NOTHING)한 요청만 실제로 처리한다.
 * 2xx 응답만 COMPLETED 로 저장해 재생하고, 오류로 끝나면 선점을 풀어 같은 요청으로 다시 시도할 수 있게 한다.
 */
@Service
public class IdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 사용자·경로·본문으로 요청을 식별하는 지문. */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ResponseEntity<String> execute(IdempotencyScope scope, String key, String fingerprint,
                                          Supplier<ResponseEntity<?>> action) {
        ResponseEntity<String> replay = claim(scope, key, fingerprint);
        if (replay != null) {
            return replay;
        }
        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            release(scope, key);
            throw e;
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            release(scope, key);
            return toJson(response);
        }
        ResponseEntity<String> json = toJson(response);
        URI location = response.getHeaders().getLocation();
        jdbc.update("""
                        update idempotency_records
                           set state = ?, response_status = ?, response_body = ?, response_location = ?
                         where scope = ? and idem_key = ?""",
                COMPLETED, response.getStatusCode().value(), json.getBody(),
                location == null ? null : location.toString(), scope.name(), key);
        return json;
    }

    /** 키를 선점하면 null, 이미 완료된 같은 요청이면 저장된 응답을 돌려준다. */
    private ResponseEntity<String> claim(IdempotencyScope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < 5; attempt++) {
            int inserted = jdbc.update("""
                            insert into idempotency_records (scope, idem_key, fingerprint, state, created_at)
                            values (?, ?, ?, ?, now())
                            on conflict (scope, idem_key) do nothing""",
                    scope.name(), key, fingerprint, IN_PROGRESS);
            if (inserted == 1) {
                return null;
            }
            List<StoredRecord> existing = jdbc.query("""
                            select fingerprint, state, response_status, response_body, response_location
                              from idempotency_records where scope = ? and idem_key = ?""",
                    (rs, i) -> new StoredRecord(rs.getString(1), rs.getString(2),
                            (Integer) rs.getObject(3), rs.getString(4), rs.getString(5)),
                    scope.name(), key);
            if (existing.isEmpty()) {
                continue; // 앞선 요청이 실패해 선점이 풀렸다. 다시 선점을 시도한다.
            }
            StoredRecord record = existing.getFirst();
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used for a different request");
            }
            if (COMPLETED.equals(record.state())) {
                return record.toResponse();
            }
            throw inProgress();
        }
        throw inProgress();
    }

    private void release(IdempotencyScope scope, String key) {
        jdbc.update("delete from idempotency_records where scope = ? and idem_key = ? and state = ?",
                scope.name(), key, IN_PROGRESS);
    }

    private ResponseEntity<String> toJson(ResponseEntity<?> response) {
        try {
            String body = response.getBody() instanceof String s ? s : objectMapper.writeValueAsString(response.getBody());
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException inProgress() {
        return new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with the same Idempotency-Key is being processed");
    }

    private record StoredRecord(String fingerprint, String state, Integer status, String body, String location) {

        ResponseEntity<String> toResponse() {
            HttpHeaders headers = new HttpHeaders();
            if (location != null) {
                headers.setLocation(URI.create(location));
            }
            headers.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.status(status).headers(headers).body(body);
        }
    }
}
