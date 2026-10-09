package com.example.order.idempotency;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등 키 저장소. 호출자는 업무 트랜잭션 바깥에서 begin/complete/release 를 순차 호출해야 한다
 * (각 메서드는 자체 짧은 트랜잭션).
 */
@Service
public class IdempotencyService {
    private static final String INSERT_SQL = """
            INSERT INTO idempotency_keys (scope, idem_key, request_hash, status, created_at)
            VALUES (?, ?, ?, 'IN_PROGRESS', ?)
            ON CONFLICT (scope, idem_key) DO NOTHING
            RETURNING id""";
    private static final String SELECT_SQL = """
            SELECT id, request_hash, status, response_status, response_body, response_location
            FROM idempotency_keys WHERE scope = ? AND idem_key = ?""";

    private record Row(long id, String hash, String status, Integer responseStatus, String body, String location) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(IdempotencyScope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Long id = jdbc.query(INSERT_SQL, (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null,
                    scope.name(), key, fingerprint, Times.now(clock));
            if (id != null) {
                return new BeginResult.Owner(id);
            }
            List<Row> rows = jdbc.query(SELECT_SQL, (rs, i) -> new Row(rs.getLong("id"), rs.getString("request_hash"),
                    rs.getString("status"), (Integer) rs.getObject("response_status"), rs.getString("response_body"),
                    rs.getString("response_location")), scope.name(), key);
            if (rows.isEmpty()) {
                continue; // 선점자가 그 사이 release 함
            }
            Row r = rows.get(0);
            if (!r.hash().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used with a different request");
            }
            if ("COMPLETED".equals(r.status())) {
                return new BeginResult.Replay(r.responseStatus(), r.body(), r.location());
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "a request with this Idempotency-Key is in progress");
        }
        throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "a request with this Idempotency-Key is in progress");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long id, int status, String body, String location) {
        jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?, completed_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS'""", status, body, location, Times.now(clock), id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(long id) {
        jdbc.update("DELETE FROM idempotency_keys WHERE id = ? AND status = 'IN_PROGRESS'", id);
    }
}
