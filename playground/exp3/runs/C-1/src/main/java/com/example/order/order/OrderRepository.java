package com.example.order.order;

import com.example.order.common.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All order persistence goes through conditional UPDATEs (status + lease guards, 02_db_design.md section 3).
 * Plain JDBC on purpose: no persistence-context staleness between a claim and the follow-up read.
 */
@Repository
public class OrderRepository {

    private static final String COLUMNS = "id, user_id, status, coupon_id, coupon_code, subtotal, discount, "
            + "total_price, idempotency_key, request_hash, created_at, expires_at, paid_at, shipped_at, "
            + "delivered_at, lease_kind, lease_token, lease_expires_at";

    private static final String NO_ACTIVE_LEASE = "(lease_token IS NULL OR lease_expires_at <= :now)";
    private static final String CLEAR_LEASE = "lease_kind = NULL, lease_token = NULL, lease_expires_at = NULL";

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> mapOrder(rs);

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OrderRow mapOrder(ResultSet rs) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getString("user_id"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("coupon_id", Long.class),
                rs.getString("coupon_code"),
                rs.getLong("subtotal"),
                rs.getLong("discount"),
                rs.getLong("total_price"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                Times.instant(rs, "created_at"),
                Times.instant(rs, "expires_at"),
                Times.instant(rs, "paid_at"),
                Times.instant(rs, "shipped_at"),
                Times.instant(rs, "delivered_at"),
                rs.getString("lease_kind"),
                rs.getObject("lease_token", UUID.class),
                Times.instant(rs, "lease_expires_at"));
    }

    // ---------- reads ----------

    public Optional<OrderRow> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM orders WHERE id = :id")
                .param("id", id).query(ORDER_MAPPER).optional();
    }

    public Optional<OrderRow> findByUserAndKey(String userId, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM orders WHERE user_id = :u AND idempotency_key = :k")
                .param("u", userId).param("k", idempotencyKey).query(ORDER_MAPPER).optional();
    }

    public List<OrderItemRow> findItems(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT order_id, line_no, product_id, quantity, unit_price FROM order_items "
                        + "WHERE order_id IN (:ids) ORDER BY order_id, line_no")
                .param("ids", orderIds)
                .query((rs, n) -> new OrderItemRow(rs.getLong("order_id"), rs.getInt("line_no"),
                        rs.getLong("product_id"), rs.getInt("quantity"), rs.getLong("unit_price")))
                .list();
    }

    /** id DESC page (size+1 rows expected from the caller). */
    public List<OrderRow> list(String userId, OrderStatus status, Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM orders WHERE 1 = 1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (beforeId != null) {
            sql.append(" AND id < :beforeId");
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (userId != null) {
            spec = spec.param("userId", userId);
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        if (beforeId != null) {
            spec = spec.param("beforeId", beforeId);
        }
        return spec.query(ORDER_MAPPER).list();
    }

    /** Pending orders past their TTL without an active lease, oldest first. */
    public List<Long> findDueIds(Instant now, String userId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' "
                + "AND expires_at <= :now AND " + NO_ACTIVE_LEASE);
        if (userId != null) {
            sql.append(" AND user_id = :userId");
        }
        sql.append(" ORDER BY expires_at LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("now", Times.ts(now)).param("limit", limit);
        if (userId != null) {
            spec = spec.param("userId", userId);
        }
        return spec.query(Long.class).list();
    }

    // ---------- creation ----------

    /** (6) returns the new id, or empty when (user_id, idempotency_key) already exists. */
    public Optional<Long> insertIfAbsent(String userId, Long couponId, String couponCode, long subtotal,
                                         String idempotencyKey, String requestHash, Instant createdAt,
                                         Instant expiresAt) {
        return jdbc.sql("INSERT INTO orders (user_id, status, coupon_id, coupon_code, subtotal, discount, "
                        + "total_price, idempotency_key, request_hash, created_at, expires_at, updated_at) "
                        + "VALUES (:userId, 'PENDING_PAYMENT', :couponId, :couponCode, :subtotal, 0, :subtotal, "
                        + ":key, :hash, :createdAt, :expiresAt, :createdAt) "
                        + "ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id")
                .param("userId", userId)
                .param("couponId", couponId, Types.BIGINT)
                .param("couponCode", couponCode, Types.VARCHAR)
                .param("subtotal", subtotal)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("createdAt", Times.ts(createdAt))
                .param("expiresAt", Times.ts(expiresAt))
                .query(Long.class).optional();
    }

    public void applyDiscount(long orderId, long discount, long totalPrice, Instant now) {
        int rows = jdbc.sql("UPDATE orders SET discount = :d, total_price = :t, updated_at = :now WHERE id = :id")
                .param("d", discount).param("t", totalPrice).param("now", Times.ts(now)).param("id", orderId)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("applyDiscount affected " + rows + " rows for order " + orderId);
        }
    }

    public void insertItems(long orderId, List<OrderItemRow> items) {
        for (OrderItemRow item : items) {
            jdbc.sql("INSERT INTO order_items (order_id, line_no, product_id, quantity, unit_price) "
                            + "VALUES (:o, :l, :p, :q, :u)")
                    .param("o", orderId).param("l", item.lineNo()).param("p", item.productId())
                    .param("q", item.quantity()).param("u", item.unitPrice())
                    .update();
        }
    }

    // ---------- claims (return affected rows: 1 = won) ----------

    /** (7) expire claim. */
    public int claimExpire(long id, Instant now) {
        OrderStatus.requireTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED);
        return jdbc.sql("UPDATE orders SET status = 'EXPIRED', updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at <= :now AND "
                        + NO_ACTIVE_LEASE)
                .param("id", id).param("now", Times.ts(now)).update();
    }

    /** (8) cancel claim for a pending order. */
    public int claimCancel(long id, Instant now) {
        OrderStatus.requireTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
        return jdbc.sql("UPDATE orders SET status = 'CANCELLED', updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at > :now AND "
                        + NO_ACTIVE_LEASE)
                .param("id", id).param("now", Times.ts(now)).update();
    }

    /** (9) payment lease. */
    public int claimPayLease(long id, UUID token, Instant now, Instant leaseUntil) {
        return jdbc.sql("UPDATE orders SET lease_kind = 'PAY', lease_token = :t, lease_expires_at = :until, "
                        + "updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at > :now AND "
                        + NO_ACTIVE_LEASE)
                .param("id", id).param("t", token).param("until", Times.ts(leaseUntil))
                .param("now", Times.ts(now)).update();
    }

    /** (10) payment completion (APPROVED -> PAID, DECLINED -> PAYMENT_FAILED); requires the lease token. */
    public int completePayment(long id, OrderStatus target, UUID token, Instant now) {
        OrderStatus.requireTransition(OrderStatus.PENDING_PAYMENT, target);
        String paidAt = target == OrderStatus.PAID ? ", paid_at = :now" : "";
        return jdbc.sql("UPDATE orders SET status = :target" + paidAt + ", " + CLEAR_LEASE
                        + ", updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND lease_token = :t")
                .param("target", target.name()).param("id", id).param("t", token).param("now", Times.ts(now))
                .update();
    }

    /** (11) refund lease. */
    public int claimRefundLease(long id, UUID token, Instant now, Instant leaseUntil) {
        return jdbc.sql("UPDATE orders SET lease_kind = 'REFUND', lease_token = :t, lease_expires_at = :until, "
                        + "updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PAID' AND " + NO_ACTIVE_LEASE)
                .param("id", id).param("t", token).param("until", Times.ts(leaseUntil))
                .param("now", Times.ts(now)).update();
    }

    /** (12) refund completion; requires the lease token. */
    public int completeRefund(long id, UUID token, Instant now) {
        OrderStatus.requireTransition(OrderStatus.PAID, OrderStatus.REFUNDED);
        return jdbc.sql("UPDATE orders SET status = 'REFUNDED', " + CLEAR_LEASE
                        + ", updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND status = 'PAID' AND lease_token = :t")
                .param("id", id).param("t", token).param("now", Times.ts(now)).update();
    }

    /** (13) ship claim. */
    public int claimShip(long id, Instant now) {
        OrderStatus.requireTransition(OrderStatus.PAID, OrderStatus.SHIPPED);
        return jdbc.sql("UPDATE orders SET status = 'SHIPPED', shipped_at = :now, updated_at = :now, "
                        + "version = version + 1 WHERE id = :id AND status = 'PAID' AND " + NO_ACTIVE_LEASE)
                .param("id", id).param("now", Times.ts(now)).update();
    }

    /** (14) deliver claim. */
    public int claimDeliver(long id, Instant now) {
        OrderStatus.requireTransition(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
        return jdbc.sql("UPDATE orders SET status = 'DELIVERED', delivered_at = :now, updated_at = :now, "
                        + "version = version + 1 WHERE id = :id AND status = 'SHIPPED'")
                .param("id", id).param("now", Times.ts(now)).update();
    }

    /** (15) release only the lease (gateway failure); status untouched. */
    public int releaseLease(long id, UUID token, Instant now) {
        return jdbc.sql("UPDATE orders SET " + CLEAR_LEASE + ", updated_at = :now, version = version + 1 "
                        + "WHERE id = :id AND lease_token = :t")
                .param("id", id).param("t", token).param("now", Times.ts(now)).update();
    }
}
