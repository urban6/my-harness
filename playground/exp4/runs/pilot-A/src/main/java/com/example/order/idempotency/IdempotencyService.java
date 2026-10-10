package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 멱등 키 처리. 키 선점·응답 기록은 업무 트랜잭션과 분리된 짧은 자동 커밋 문장으로 수행한다.
 * 2xx로 끝난 요청만 저장하고, 예외로 끝나면 키를 풀어 같은 요청을 다시 시도할 수 있게 한다.
 */
@Service
public class IdempotencyService {

    /** 처리 중 상태로 이 시간 넘게 남아 있으면 비정상 종료로 보고 키를 회수한다. */
    private static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public StoredResponse execute(String scope, String key, String fingerprint, Supplier<StoredResponse> action) {
        while (true) {
            Instant now = clock.instant();
            int inserted = jdbc.update("""
                    insert into idempotency_keys (scope, idem_key, fingerprint, state, created_at)
                    values (?, ?, ?, 'IN_PROGRESS', ?)
                    on conflict (scope, idem_key) do nothing
                    """, scope, key, fingerprint, Timestamp.from(now));
            if (inserted == 1) {
                break;
            }
            Row row = find(scope, key);
            if (row == null) {
                continue; // 그 사이 풀렸다. 다시 선점을 시도한다.
            }
            if (!row.fingerprint.equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used with a different request");
            }
            if ("DONE".equals(row.state)) {
                return new StoredResponse(row.responseStatus, row.responseBody, row.responseLocation);
            }
            if (row.createdAt.isBefore(now.minus(STALE_AFTER))) {
                jdbc.update("""
                        delete from idempotency_keys
                        where scope = ? and idem_key = ? and state = 'IN_PROGRESS' and created_at = ?
                        """, scope, key, Timestamp.from(row.createdAt));
                continue;
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }

        StoredResponse response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            release(scope, key);
            throw e;
        }
        jdbc.update("""
                update idempotency_keys
                set state = 'DONE', response_status = ?, response_body = ?, response_location = ?
                where scope = ? and idem_key = ?
                """, response.status(), response.body(), response.location(), scope, key);
        return response;
    }

    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void release(String scope, String key) {
        try {
            jdbc.update("delete from idempotency_keys where scope = ? and idem_key = ?", scope, key);
        } catch (RuntimeException ignored) {
            // 풀지 못해도 STALE_AFTER 뒤에 회수된다.
        }
    }

    private Row find(String scope, String key) {
        List<Row> rows = jdbc.query("""
                select fingerprint, state, response_status, response_body, response_location, created_at
                from idempotency_keys where scope = ? and idem_key = ?
                """, (rs, i) -> new Row(rs.getString("fingerprint"), rs.getString("state"),
                rs.getInt("response_status"), rs.getString("response_body"),
                rs.getString("response_location"), rs.getTimestamp("created_at").toInstant()), scope, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record Row(String fingerprint, String state, int responseStatus, String responseBody,
                       String responseLocation, Instant createdAt) {
    }
}
