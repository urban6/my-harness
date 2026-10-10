package com.example.order.idempotency;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 멱등 키 저장소. 키를 먼저 IN_PROGRESS로 선점(INSERT ... ON CONFLICT DO NOTHING)한 요청만 실제로 처리한다.
 * 각 문장은 자동 커밋되어 다른 요청이 곧바로 선점 결과를 본다.
 */
@Service
public class IdempotencyService {

    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Claim permits Acquired, Replay {
    }

    public record Acquired() implements Claim {
    }

    public record Replay(int status, String body, String location) implements Claim {
    }

    private record Row(String fingerprint, String status, Integer responseStatus, String responseBody, String location) {
    }

    /**
     * 키를 선점한다. 이미 완료된 같은 요청이면 저장된 응답을, 다른 요청이면 422를, 처리 중이면 409를 돌려준다.
     */
    public Claim claim(IdempotencyScope scope, String key, String fingerprint) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            int inserted = jdbc.sql("""
                            insert into idempotency_records (scope, idem_key, fingerprint, status)
                            values (:scope, :key, :fingerprint, 'IN_PROGRESS')
                            on conflict do nothing
                            """)
                    .param("scope", scope.name())
                    .param("key", key)
                    .param("fingerprint", fingerprint)
                    .update();
            if (inserted == 1) {
                return new Acquired();
            }
            Optional<Row> existing = find(scope, key);
            if (existing.isEmpty()) {
                continue; // 앞선 요청이 실패해 키가 풀렸다. 다시 선점을 시도한다.
            }
            Row row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "같은 Idempotency-Key로 다른 요청이 들어왔습니다");
            }
            if ("COMPLETED".equals(row.status())) {
                return new Replay(row.responseStatus(), row.responseBody(), row.location());
            }
            break;
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 Idempotency-Key의 요청을 처리 중입니다");
    }

    public void complete(IdempotencyScope scope, String key, int status, String body, String location) {
        jdbc.sql("""
                        update idempotency_records
                        set status = 'COMPLETED', response_status = :status, response_body = :body, location = :location
                        where scope = :scope and idem_key = :key
                        """)
                .param("status", status)
                .param("body", body)
                .param("location", location)
                .param("scope", scope.name())
                .param("key", key)
                .update();
    }

    /** 처리 실패 시 키를 풀어 같은 요청을 다시 시도할 수 있게 한다. */
    public void release(IdempotencyScope scope, String key) {
        jdbc.sql("delete from idempotency_records where scope = :scope and idem_key = :key and status = 'IN_PROGRESS'")
                .param("scope", scope.name())
                .param("key", key)
                .update();
    }

    private Optional<Row> find(IdempotencyScope scope, String key) {
        return jdbc.sql("""
                        select fingerprint, status, response_status, response_body, location
                        from idempotency_records where scope = :scope and idem_key = :key
                        """)
                .param("scope", scope.name())
                .param("key", key)
                .query((rs, rowNum) -> new Row(rs.getString("fingerprint"), rs.getString("status"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body"), rs.getString("location")))
                .optional();
    }
}
