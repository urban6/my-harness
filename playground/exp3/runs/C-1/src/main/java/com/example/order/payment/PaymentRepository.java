package com.example.order.payment;

import com.example.order.common.Times;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentRow> findByOrderId(long orderId) {
        return jdbc.sql("SELECT id, order_id, idempotency_key, request_hash, amount, status, pg_payment_id, "
                        + "attempt_count FROM payments WHERE order_id = :o")
                .param("o", orderId)
                .query((rs, n) -> new PaymentRow(rs.getLong("id"), rs.getLong("order_id"),
                        rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getLong("amount"),
                        PaymentStatus.valueOf(rs.getString("status")), rs.getString("pg_payment_id"),
                        rs.getInt("attempt_count")))
                .optional();
    }

    /** First attempt inserts; a retry (same key/card, verified by the caller) bumps attempt_count. */
    public void upsertInitiated(long orderId, String idempotencyKey, String requestHash, long amount, Instant now) {
        jdbc.sql("INSERT INTO payments (order_id, idempotency_key, request_hash, amount, status, attempt_count, "
                        + "created_at, updated_at) VALUES (:o, :k, :h, :a, 'INITIATED', 1, :now, :now) "
                        + "ON CONFLICT (order_id) DO UPDATE SET attempt_count = payments.attempt_count + 1, "
                        + "updated_at = :now")
                .param("o", orderId).param("k", idempotencyKey).param("h", requestHash).param("a", amount)
                .param("now", Times.ts(now)).update();
    }

    public void markApproved(long orderId, String pgPaymentId, Instant now) {
        requireOne(jdbc.sql("UPDATE payments SET status = 'APPROVED', pg_payment_id = :p, decided_at = :now, "
                        + "updated_at = :now, last_error = NULL WHERE order_id = :o AND status = 'INITIATED'")
                .param("p", pgPaymentId).param("o", orderId).param("now", Times.ts(now)).update(), orderId);
    }

    public void markDeclined(long orderId, Instant now) {
        requireOne(jdbc.sql("UPDATE payments SET status = 'DECLINED', decided_at = :now, updated_at = :now, "
                        + "last_error = NULL WHERE order_id = :o AND status = 'INITIATED'")
                .param("o", orderId).param("now", Times.ts(now)).update(), orderId);
    }

    public void markRefunded(long orderId, Instant now) {
        requireOne(jdbc.sql("UPDATE payments SET status = 'REFUNDED', refunded_at = :now, updated_at = :now, "
                        + "last_error = NULL WHERE order_id = :o AND status = 'APPROVED'")
                .param("o", orderId).param("now", Times.ts(now)).update(), orderId);
    }

    public void recordError(long orderId, String error, Instant now) {
        String truncated = error == null ? null : (error.length() > 255 ? error.substring(0, 255) : error);
        jdbc.sql("UPDATE payments SET last_error = :e, updated_at = :now WHERE order_id = :o")
                .param("e", truncated).param("o", orderId).param("now", Times.ts(now)).update();
    }

    private static void requireOne(int rows, long orderId) {
        if (rows != 1) {
            throw new IllegalStateException("payment row for order " + orderId + " was not in the expected state");
        }
    }
}
