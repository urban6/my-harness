package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key 처리 (R4).
 * <ol>
 *   <li>키를 IN_PROGRESS로 선점한다. 이미 있으면 요청 지문이 다르면 422, 완료됐으면 저장된 응답을 재생, 처리 중이면 409.</li>
 *   <li>처리 결과가 2xx면 응답을 저장하고, 그 밖(예외 포함)이면 키를 지워 같은 요청으로 다시 시도할 수 있게 한다.</li>
 * </ol>
 * 각 단계는 독립된 짧은 트랜잭션(자동 커밋)으로 실행되어 업무 트랜잭션과 연결을 겹쳐 잡지 않는다.
 */
@Service
public class IdempotencyService {

    public static final String SCOPE_CREATE_ORDER = "CREATE_ORDER";
    public static final String SCOPE_PAY_ORDER = "PAY_ORDER";

    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @param fingerprint 요청을 식별하는 값(사용자·경로·본문). JSON으로 직렬화해 해시한다.
     */
    public ResponseEntity<?> execute(String scope, String key, Object fingerprint, Supplier<ResponseEntity<?>> action) {
        String requestHash = hash(fingerprint);
        StoredResponse replay = acquire(scope, key, requestHash);
        if (replay != null) {
            return replay.toResponse();
        }

        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            release(scope, key);
            throw e;
        }

        if (response.getStatusCode().is2xxSuccessful()) {
            complete(scope, key, response);
        } else {
            release(scope, key);
        }
        return response;
    }

    private StoredResponse acquire(String scope, String key, String requestHash) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int inserted = jdbc.update("""
                    insert into idempotency_keys (scope, idem_key, request_hash, status, created_at)
                    values (?, ?, ?, 'IN_PROGRESS', now())
                    on conflict do nothing
                    """, scope, key, requestHash);
            if (inserted == 1) {
                return null;
            }
            List<KeyRow> rows = jdbc.query("""
                    select request_hash, status, response_status, response_body, response_location
                    from idempotency_keys where scope = ? and idem_key = ?
                    """, (rs, i) -> new KeyRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                    rs.getString(4), rs.getString(5)), scope, key);
            if (rows.isEmpty()) {
                continue; // 앞선 요청이 실패해 키가 방금 지워졌다. 다시 선점을 시도한다.
            }
            KeyRow row = rows.get(0);
            if (!row.requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used for a different request");
            }
            if ("COMPLETED".equals(row.status())) {
                return new StoredResponse(row.responseStatus(), row.responseBody(), row.responseLocation());
            }
            throw inProgress();
        }
        throw inProgress();
    }

    private void complete(String scope, String key, ResponseEntity<?> response) {
        URI location = response.getHeaders().getLocation();
        jdbc.update("""
                        update idempotency_keys
                        set status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?
                        where scope = ? and idem_key = ?
                        """,
                response.getStatusCode().value(), toJson(response.getBody()),
                location == null ? null : location.toString(), scope, key);
    }

    private void release(String scope, String key) {
        jdbc.update("delete from idempotency_keys where scope = ? and idem_key = ? and status = 'IN_PROGRESS'",
                scope, key);
    }

    private String hash(Object fingerprint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toJson(fingerprint).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException inProgress() {
        return new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with the same Idempotency-Key is being processed");
    }

    private record KeyRow(String requestHash, String status, int responseStatus, String responseBody,
                          String responseLocation) {
    }

    private record StoredResponse(int status, String body, String location) {

        ResponseEntity<String> toResponse() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (location != null) {
                headers.setLocation(URI.create(location));
            }
            return ResponseEntity.status(status).headers(headers).body(body);
        }
    }
}
