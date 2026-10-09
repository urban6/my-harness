package com.example.order.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 멱등 키 저장소. 처리 트랜잭션과 독립적으로 즉시 커밋되어야 하므로(선점이 다른 요청에 바로 보여야 함)
 * JPA 영속성 컨텍스트 대신 JdbcTemplate 으로 다룬다.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 키를 선점한다. 이미 있으면 false. */
    public boolean tryAcquire(String scope, String key, String requestHash) {
        int inserted = jdbcTemplate.update("""
                insert into idempotency_keys (scope, idempotency_key, request_hash, status)
                values (?, ?, ?, 'IN_PROGRESS')
                on conflict do nothing
                """, scope, key, requestHash);
        return inserted == 1;
    }

    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbcTemplate.query("""
                        select request_hash, status, response_status, response_body, response_location
                        from idempotency_keys where scope = ? and idempotency_key = ?
                        """,
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        "COMPLETED".equals(rs.getString("status")),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getString("response_location")),
                scope, key).stream().findFirst();
    }

    public void complete(String scope, String key, int status, String body, String location) {
        jdbcTemplate.update("""
                update idempotency_keys
                set status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?
                where scope = ? and idempotency_key = ?
                """, status, body, location, scope, key);
    }

    public void release(String scope, String key) {
        jdbcTemplate.update("delete from idempotency_keys where scope = ? and idempotency_key = ? and status = 'IN_PROGRESS'",
                scope, key);
    }
}
