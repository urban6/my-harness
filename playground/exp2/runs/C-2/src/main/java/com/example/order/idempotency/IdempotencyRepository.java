package com.example.order.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate 기반. 활성 트랜잭션이 있으면 그 커넥션에 참여한다. */
@Repository
public class IdempotencyRepository {

    public record Row(long id, String fingerprint, String status, Integer responseStatus, String responseBody,
            String responseLocation) {
        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 삽입되면 새 id, 이미 키가 있으면 empty. */
    public Optional<Long> tryInsert(IdempotencyScope scope, String key, String fingerprint, Instant now) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO idempotency_records(scope, idempotency_key, request_fingerprint, status, created_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                RETURNING id
                """, Long.class, scope.name(), key, fingerprint, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public Optional<Row> find(IdempotencyScope scope, String key) {
        List<Row> rows = jdbc.query("""
                SELECT id, request_fingerprint, status, response_status, response_body, response_location
                FROM idempotency_records WHERE scope = ? AND idempotency_key = ?
                """, (ResultSet rs, int i) -> mapRow(rs), scope.name(), key);
        return rows.stream().findFirst();
    }

    private static Row mapRow(ResultSet rs) throws SQLException {
        int rs0 = rs.getInt("response_status");
        Integer responseStatus = rs.wasNull() ? null : rs0;
        return new Row(rs.getLong("id"), rs.getString("request_fingerprint"), rs.getString("status"),
                responseStatus, rs.getString("response_body"), rs.getString("response_location"));
    }

    public int complete(long id, int responseStatus, String body, String location, Instant now) {
        return jdbc.update("""
                UPDATE idempotency_records
                SET status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?, completed_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS'
                """, responseStatus, body, location, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), id);
    }

    public int deleteInProgress(long id) {
        return jdbc.update("DELETE FROM idempotency_records WHERE id = ? AND status = 'IN_PROGRESS'", id);
    }
}
