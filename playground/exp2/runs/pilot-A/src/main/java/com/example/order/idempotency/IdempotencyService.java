package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 멱등 키로 요청을 한 번만 처리하고, 성공(2xx) 응답을 저장해 재생한다 (R4).
 * 처리 중에는 IN_PROGRESS 행이 키를 점유하므로 동시에 들어온 같은 키 요청은 409로 끝난다.
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int status, String body, String location) {
    }

    private record Record(String fingerprint, String status, Integer responseStatus, String responseBody,
                          String location) {
    }

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String fingerprint(String userId, String path, String canonicalBody) {
        String raw = (userId == null ? "\u0000" : userId) + "\n" + path + "\n" + canonicalBody;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ResponseEntity<String> execute(IdempotencyScope scope, String key, String fingerprint,
                                          Supplier<StoredResponse> action) {
        StoredResponse replay = acquire(scope, key, fingerprint);
        if (replay != null) {
            return toEntity(replay);
        }
        StoredResponse response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            release(scope, key);
            throw e;
        }
        if (HttpStatus.valueOf(response.status()).is2xxSuccessful()) {
            jdbc.update("""
                    UPDATE idempotency_records
                       SET status = ?, response_status = ?, response_body = ?, location = ?
                     WHERE scope = ? AND idem_key = ?""",
                    COMPLETED, response.status(), response.body(), response.location(), scope.name(), key);
        } else {
            release(scope, key);
        }
        return toEntity(response);
    }

    /** 키를 점유하면 null, 이미 완료된 같은 요청이면 저장된 응답을 돌려준다. */
    private StoredResponse acquire(IdempotencyScope scope, String key, String fingerprint) {
        while (true) {
            int inserted = jdbc.update("""
                    INSERT INTO idempotency_records (scope, idem_key, fingerprint, status, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (scope, idem_key) DO NOTHING""",
                    scope.name(), key, fingerprint, IN_PROGRESS, Timestamp.from(Times.now(clock)));
            if (inserted == 1) {
                return null;
            }
            List<Record> existing = jdbc.query("""
                    SELECT fingerprint, status, response_status, response_body, location
                      FROM idempotency_records WHERE scope = ? AND idem_key = ?""",
                    (rs, i) -> new Record(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                            rs.getString(4), rs.getString(5)),
                    scope.name(), key);
            if (existing.isEmpty()) {
                continue; // 선행 요청이 실패해 키가 방금 풀렸다. 다시 점유를 시도한다.
            }
            Record record = existing.getFirst();
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used for a different request");
            }
            if (COMPLETED.equals(record.status())) {
                return new StoredResponse(record.responseStatus(), record.responseBody(), record.location());
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }
    }

    private void release(IdempotencyScope scope, String key) {
        jdbc.update("DELETE FROM idempotency_records WHERE scope = ? AND idem_key = ? AND status = ?",
                scope.name(), key, IN_PROGRESS);
    }

    private static ResponseEntity<String> toEntity(StoredResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (response.location() != null) {
            builder.header("Location", response.location());
        }
        return builder.body(response.body());
    }
}
