package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Idempotency-Key 처리 (R4).
 * 키를 IN_PROGRESS로 먼저 선점(insert)한 요청만 실제로 처리한다. 2xx 응답은 저장해 재생하고,
 * 오류로 끝나면 키를 지워 같은 요청으로 다시 시도할 수 있게 한다.
 */
@Service
public class IdempotencyService {

    public enum Scope { CREATE_ORDER, PAY_ORDER }

    private record Stored(String fingerprint, String state, Integer status, String body, String location) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** userId·경로·본문으로 요청 지문을 만든다. 본문은 파싱된 요청 객체를 다시 직렬화해 표기 차이를 없앤다. */
    public String fingerprint(String userId, String path, Object body) {
        try {
            String canonical = (userId == null ? "" : userId) + "\n" + path + "\n" + objectMapper.writeValueAsString(body);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ResponseEntity<?> execute(Scope scope, String key, String fingerprint, Supplier<ResponseEntity<?>> action) {
        ResponseEntity<?> replay = acquire(scope, key, fingerprint);
        if (replay != null) {
            return replay;
        }
        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            release(scope, key);
            throw e;
        }
        if (response.getStatusCode().is2xxSuccessful()) {
            store(scope, key, response);
        } else {
            release(scope, key);
        }
        return response;
    }

    /** 키를 선점하면 null, 이미 완료된 같은 요청이면 저장된 응답을 돌려준다. */
    private ResponseEntity<?> acquire(Scope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < 5; attempt++) {
            int inserted = jdbc.update("""
                    insert into idempotency_record (scope, idem_key, fingerprint, state, created_at)
                    values (?, ?, ?, 'IN_PROGRESS', now())
                    on conflict do nothing
                    """, scope.name(), key, fingerprint);
            if (inserted == 1) {
                return null;
            }
            List<Stored> rows = jdbc.query("""
                            select fingerprint, state, response_status, response_body, location
                            from idempotency_record where scope = ? and idem_key = ?
                            """,
                    (rs, i) -> new Stored(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                            rs.getString(4), rs.getString(5)),
                    scope.name(), key);
            if (rows.isEmpty()) {
                continue; // 앞선 요청이 실패해 키가 방금 풀렸다
            }
            Stored stored = rows.get(0);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_MISMATCH",
                        "Idempotency-Key was already used for a different request");
            }
            if ("COMPLETED".equals(stored.state())) {
                return replay(stored);
            }
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is in progress");
        }
        throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is in progress");
    }

    private ResponseEntity<?> replay(Stored stored) {
        try {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
                    .contentType(MediaType.APPLICATION_JSON);
            if (stored.location() != null) {
                builder.header(HttpHeaders.LOCATION, stored.location());
            }
            return builder.body(objectMapper.readTree(stored.body()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void store(Scope scope, String key, ResponseEntity<?> response) {
        try {
            String location = response.getHeaders().getLocation() == null
                    ? null : response.getHeaders().getLocation().toString();
            jdbc.update("""
                            update idempotency_record
                            set state = 'COMPLETED', response_status = ?, response_body = ?, location = ?
                            where scope = ? and idem_key = ?
                            """,
                    response.getStatusCode().value(), objectMapper.writeValueAsString(response.getBody()), location,
                    scope.name(), key);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void release(Scope scope, String key) {
        jdbc.update("delete from idempotency_record where scope = ? and idem_key = ? and state = 'IN_PROGRESS'",
                scope.name(), key);
    }
}
