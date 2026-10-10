package com.example.order.common.idempotency;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 멱등 키 저장소. 각 문장은 트랜잭션 밖에서 바로 커밋되어, 동시에 들어온 같은 키의 요청이 서로를 볼 수 있다.
 */
@Repository
class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 키를 선점하면 true, 이미 다른 요청이 쥐고 있으면 false. */
    boolean tryAcquire(IdempotencyScope scope, String key, String fingerprint) {
        int inserted = jdbcTemplate.update("""
                insert into idempotency_keys (scope, idempotency_key, fingerprint, status)
                values (?, ?, ?, 'IN_PROGRESS')
                on conflict do nothing
                """, scope.name(), key, fingerprint);
        return inserted == 1;
    }

    Optional<IdempotencyRecord> find(IdempotencyScope scope, String key) {
        return jdbcTemplate.query("""
                        select fingerprint, status, response_status, response_body, response_location
                          from idempotency_keys
                         where scope = ? and idempotency_key = ?
                        """,
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("fingerprint"),
                        "COMPLETED".equals(rs.getString("status")),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body"),
                        rs.getString("response_location")),
                scope.name(), key).stream().findFirst();
    }

    void complete(IdempotencyScope scope, String key, int status, String body, String location) {
        jdbcTemplate.update("""
                update idempotency_keys
                   set status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?
                 where scope = ? and idempotency_key = ?
                """, status, body, location, scope.name(), key);
    }

    void release(IdempotencyScope scope, String key) {
        jdbcTemplate.update("delete from idempotency_keys where scope = ? and idempotency_key = ? and status = 'IN_PROGRESS'",
                scope.name(), key);
    }
}
