package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.config.OrderProperties;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 멱등 키 (R4, 02 문서 9절). begin/release 는 트랜잭션 밖에서 호출되어 각각 autocommit 으로 즉시 커밋된다.
 * complete 는 비즈니스 트랜잭션 안에서 호출되어 같은 커넥션(JpaTransactionManager 가 노출)에 참여한다.
 */
@Service
public class IdempotencyService {

    public enum Endpoint {
        ORDER_CREATE, ORDER_PAY
    }

    public record Attempt(long id, UUID attemptId) {
    }

    public sealed interface BeginResult permits Proceed, Replay {
    }

    public record Proceed(Attempt attempt) implements BeginResult {
    }

    public record Replay(StoredResponse response) implements BeginResult {
    }

    private record Row(long id, String fingerprint, String status, UUID attemptId, Instant startedAt,
                       Integer responseStatus, String contentType, String location, String body) {
    }

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final OrderProperties props;

    public IdempotencyService(JdbcTemplate jdbc, AppClock clock, OrderProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    /** 지문 불일치 -> 422 / COMPLETED -> 재생 / IN_PROGRESS(신선) -> 409 / 없음 -> 선점 / 오래된 IN_PROGRESS -> 인수. */
    public BeginResult begin(Endpoint endpoint, String key, String fingerprint) {
        for (int i = 0; i < 3; i++) {
            Instant now = clock.now();
            UUID attemptId = UUID.randomUUID();
            List<Long> inserted = jdbc.query(
                    "INSERT INTO idempotency_keys (endpoint, idem_key, request_fingerprint, status, attempt_id, started_at) "
                            + "VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?) "
                            + "ON CONFLICT (endpoint, idem_key) DO NOTHING RETURNING id",
                    (rs, n) -> rs.getLong(1),
                    endpoint.name(), key, fingerprint, attemptId, utc(now));
            if (!inserted.isEmpty()) {
                return new Proceed(new Attempt(inserted.get(0), attemptId));
            }
            Row row = find(endpoint, key);
            if (row == null) {
                continue; // 동시 해제로 사라짐 -> 재시도
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "Idempotency-Key was already used with a different request.");
            }
            if ("COMPLETED".equals(row.status())) {
                return new Replay(new StoredResponse(row.responseStatus(), row.contentType(), row.location(), row.body()));
            }
            Instant staleBefore = now.minus(props.idempotency().inProgressTimeout());
            if (row.startedAt().isAfter(staleBefore)) {
                throw inProgress();
            }
            // 크래시 잔재: CAS 로 인수
            int updated = jdbc.update(
                    "UPDATE idempotency_keys SET attempt_id = ?, started_at = ? "
                            + "WHERE id = ? AND attempt_id = ? AND status = 'IN_PROGRESS'",
                    attemptId, utc(now), row.id(), row.attemptId());
            if (updated == 1) {
                return new Proceed(new Attempt(row.id(), attemptId));
            }
            throw inProgress();
        }
        throw inProgress();
    }

    /** 비즈니스 트랜잭션 안에서 호출. 인수당했다면(0행) 예외를 던져 비즈니스 롤백. */
    public void complete(Attempt attempt, StoredResponse response) {
        int updated = jdbc.update(
                "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?, response_content_type = ?, "
                        + "response_location = ?, response_body = ?, completed_at = ? "
                        + "WHERE id = ? AND attempt_id = ? AND status = 'IN_PROGRESS'",
                response.status(), response.contentType(), response.location(), response.body(),
                utc(clock.now()), attempt.id(), attempt.attemptId());
        if (updated != 1) {
            throw inProgress();
        }
    }

    /** 2xx 가 아닌 모든 종료에서 호출. COMPLETED 는 절대 지우지 않는다 (R4.4). */
    public void release(Attempt attempt) {
        try {
            jdbc.update("DELETE FROM idempotency_keys WHERE id = ? AND attempt_id = ? AND status = 'IN_PROGRESS'",
                    attempt.id(), attempt.attemptId());
        } catch (RuntimeException e) {
            // 해제 실패는 원래 오류를 가리지 않는다. 오래된 IN_PROGRESS 는 in-progress-timeout 후 인수된다.
        }
    }

    private Row find(Endpoint endpoint, String key) {
        List<Row> rows = jdbc.query(
                "SELECT id, request_fingerprint, status, attempt_id, started_at, response_status, "
                        + "response_content_type, response_location, response_body "
                        + "FROM idempotency_keys WHERE endpoint = ? AND idem_key = ?",
                (rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("request_fingerprint"),
                        rs.getString("status"),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_content_type"),
                        rs.getString("response_location"),
                        rs.getString("response_body")),
                endpoint.name(), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static ApiException inProgress() {
        return new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with the same Idempotency-Key is being processed.");
    }
}
