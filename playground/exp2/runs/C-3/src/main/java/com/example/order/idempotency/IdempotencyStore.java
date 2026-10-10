package com.example.order.idempotency;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IdempotencyStore {

    public sealed interface BeginResult permits Acquired, Replay {
    }

    /** 키 선점 성공: 이 요청이 owner */
    public record Acquired(long recordId) implements BeginResult {
    }

    /** 이미 COMPLETED: 저장된 응답을 재생 */
    public record Replay(StoredResponse response) implements BeginResult {
    }

    private record Row(long id, String requestHash, String status, Integer responseStatus, String responseBody,
                       String responseLocation) {
    }

    private static final int MAX_ATTEMPTS = 3;

    private static final String INSERT_SQL = """
            INSERT INTO idempotency_keys (scope, idem_key, request_hash, status)
            VALUES (?, ?, ?, 'IN_PROGRESS')
            ON CONFLICT (scope, idem_key) DO NOTHING
            RETURNING id""";

    private static final String SELECT_SQL = """
            SELECT id, request_hash, status, response_status, response_body, response_location
              FROM idempotency_keys WHERE scope = ? AND idem_key = ?""";

    private static final String COMPLETE_SQL = """
            UPDATE idempotency_keys
               SET status = 'COMPLETED', response_status = ?, response_body = ?, response_location = ?,
                   completed_at = now()
             WHERE id = ? AND status = 'IN_PROGRESS'""";

    private static final String RELEASE_SQL =
            "DELETE FROM idempotency_keys WHERE id = ? AND status = 'IN_PROGRESS'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public IdempotencyStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 비즈니스 트랜잭션 밖에서 호출한다. 선점 INSERT는 별도 트랜잭션으로 즉시 커밋된다. */
    public BeginResult begin(IdempotencyScope scope, String key, String requestHash) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<Long> ids = requiresNew.execute(status ->
                    jdbc.query(INSERT_SQL, (rs, i) -> rs.getLong(1), scope.name(), key, requestHash));
            if (ids != null && !ids.isEmpty()) {
                return new Acquired(ids.get(0));
            }
            List<Row> rows = jdbc.query(SELECT_SQL, (rs, i) -> new Row(
                    rs.getLong("id"),
                    rs.getString("request_hash"),
                    rs.getString("status"),
                    (Integer) rs.getObject("response_status"),
                    rs.getString("response_body"),
                    rs.getString("response_location")), scope.name(), key);
            if (rows.isEmpty()) {
                continue; // owner가 그 사이 실패해 삭제 -> 다시 선점 시도
            }
            Row row = rows.get(0);
            if (!row.requestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                        "같은 Idempotency-Key로 다른 요청이 전달되었습니다.");
            }
            if ("COMPLETED".equals(row.status())) {
                return new Replay(new StoredResponse(row.responseStatus(), row.responseBody(), row.responseLocation()));
            }
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 키의 요청이 처리 중입니다.");
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "같은 키의 요청이 처리 중입니다.");
    }

    /** 비즈니스 트랜잭션 안에서 호출한다(같은 커넥션에 참여). */
    public void complete(long recordId, int status, String bodyJson, String location) {
        int updated = jdbc.update(COMPLETE_SQL, status, bodyJson, location, recordId);
        if (updated != 1) {
            throw new IllegalStateException("멱등 레코드 완료 처리 실패: id=" + recordId);
        }
    }

    /** 오류로 끝난 요청의 키를 삭제한다. 비즈니스 트랜잭션이 끝난 뒤 별도 트랜잭션으로 실행한다. */
    public void release(long recordId) {
        requiresNew.executeWithoutResult(status -> jdbc.update(RELEASE_SQL, recordId));
    }
}
