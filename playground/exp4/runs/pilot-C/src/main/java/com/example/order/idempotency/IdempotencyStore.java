package com.example.order.idempotency;

import com.example.order.common.Times;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** idempotency_records 접근. 각 메서드의 트랜잭션 경계가 서로 다르므로 파사드와 별도 빈으로 둔다. */
@Component
public class IdempotencyStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public IdempotencyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 키 선점. 짧은 트랜잭션으로 즉시 커밋. 선점 성공 시 레코드 id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> claim(IdempotencyScope scope, String key, String userId, String path, String bodyHash) {
        return jdbc.sql("""
                        INSERT INTO idempotency_records
                            (scope, idem_key, user_id, request_path, body_hash, status, created_at)
                        VALUES (:scope, :key, :userId, :path, :hash, 'IN_PROGRESS', :now)
                        ON CONFLICT (scope, idem_key) DO NOTHING
                        RETURNING id
                        """)
                .param("scope", scope.name())
                .param("key", key)
                .param("userId", userId)
                .param("path", path)
                .param("hash", bodyHash)
                .param("now", Times.utc(Times.now(clock)))
                .query(Long.class)
                .optional();
    }

    /** 락 없는 조회 (트랜잭션 밖). */
    public Optional<IdempotencyRecord> find(IdempotencyScope scope, String key) {
        return jdbc.sql("""
                        SELECT id, user_id, request_path, body_hash, status,
                               response_status, response_body, response_location
                          FROM idempotency_records WHERE scope = :scope AND idem_key = :key
                        """)
                .param("scope", scope.name())
                .param("key", key)
                .query((rs, n) -> new IdempotencyRecord(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("request_path"),
                        rs.getString("body_hash"),
                        rs.getString("status"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getString("response_location")))
                .optional();
    }

    /** 비즈니스 트랜잭션 안에서 호출: 업무 변경과 완료 기록이 원자적이다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long id, int status, String body, String location) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_records
                           SET status = 'COMPLETED', response_status = :s, response_body = :b,
                               response_location = :loc, completed_at = :now
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("s", status)
                .param("b", body)
                .param("loc", location)
                .param("now", Times.utc(Times.now(clock)))
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record " + id + " is not in progress");
        }
    }

    /** 오류 시 키 해제 (새 트랜잭션). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(long id) {
        jdbc.sql("DELETE FROM idempotency_records WHERE id = :id AND status = 'IN_PROGRESS'")
                .param("id", id)
                .update();
    }
}
