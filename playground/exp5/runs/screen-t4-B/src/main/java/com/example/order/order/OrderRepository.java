package com.example.order.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String COLS = "id, tenant_id, user_id, status, coupon_code, subtotal, discount, "
            + "total_price, created_at, expires_at, paid_at, payment_id";

    private static final RowMapper<Order> MAPPER = (rs, i) -> new Order(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("user_id"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getString("coupon_code"), rs.getLong("subtotal"), rs.getLong("discount"), rs.getLong("total_price"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("paid_at", OffsetDateTime.class), rs.getString("payment_id"), List.of());

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public OrderRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public long insert(String tenantId, String userId, String couponCode, long subtotal, long discount,
            long totalPrice, OffsetDateTime createdAt, OffsetDateTime expiresAt, List<Order.Item> items) {
        Long id = jdbc.queryForObject("INSERT INTO orders (tenant_id, user_id, status, coupon_code, subtotal, "
                        + "discount, total_price, created_at, expires_at) "
                        + "VALUES (?, ?, 'PENDING_PAYMENT', ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, tenantId, userId, couponCode, subtotal, discount, totalPrice, createdAt, expiresAt);
        int line = 0;
        for (Order.Item item : items) {
            jdbc.update("INSERT INTO order_items (order_id, line_no, product_id, quantity, unit_price) "
                    + "VALUES (?, ?, ?, ?, ?)", id, line++, item.productId(), item.quantity(), item.unitPrice());
        }
        return id;
    }

    /** Another tenant's order is reported as absent (M2.2). */
    public Optional<Order> find(String tenantId, long id) {
        return one("SELECT " + COLS + " FROM orders WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    public Optional<Order> lock(String tenantId, long id) {
        return one("SELECT " + COLS + " FROM orders WHERE tenant_id = ? AND id = ? FOR UPDATE", tenantId, id);
    }

    private Optional<Order> one(String sql, String tenantId, long id) {
        List<Order> rows = jdbc.query(sql, MAPPER, tenantId, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withItems(rows).get(0));
    }

    /** PENDING_PAYMENT orders (of any tenant) whose expiresAt has passed, locked; rows locked by others are skipped. */
    public List<Order> lockExpired(OffsetDateTime now, int limit) {
        List<Order> rows = jdbc.query("SELECT " + COLS + " FROM orders WHERE status = 'PENDING_PAYMENT' "
                + "AND expires_at <= ? ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", MAPPER, now, limit);
        return withItems(rows);
    }

    public void updateStatus(long id, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), id);
    }

    public void markPaid(long id, OffsetDateTime paidAt, String paymentId) {
        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ?, payment_id = ? WHERE id = ?",
                paidAt, paymentId, id);
    }

    /** R2.5/R2.6: orders of this user that are currently using the coupon. */
    public boolean userHasActiveCouponOrder(String tenantId, String userId, String couponCode) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM orders WHERE tenant_id = ? AND user_id = ? "
                        + "AND coupon_code = ? AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED'))",
                Boolean.class, tenantId, userId, couponCode);
        return Boolean.TRUE.equals(b);
    }

    /** Keyset page of one tenant ordered by (created_at DESC, id DESC); fetches limit rows. */
    public List<Order> page(String tenantId, String userId, OrderStatus status, OffsetDateTime afterCreatedAt,
            Long afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM orders WHERE tenant_id = :tenantId");
        MapSqlParameterSource p = new MapSqlParameterSource("tenantId", tenantId);
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null) {
            sql.append(" AND status = :status");
            p.addValue("status", status.name());
        }
        if (afterCreatedAt != null) {
            sql.append(" AND (created_at, id) < (:cAt, :cId)");
            p.addValue("cAt", afterCreatedAt);
            p.addValue("cId", afterId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        p.addValue("limit", limit);
        return withItems(named.query(sql.toString(), p, MAPPER));
    }

    private List<Order> withItems(List<Order> orders) {
        if (orders.isEmpty()) {
            return orders;
        }
        Map<Long, List<Order.Item>> byOrder = new LinkedHashMap<>();
        Collection<Long> ids = orders.stream().map(Order::id).toList();
        for (Long id : ids) {
            byOrder.put(id, new ArrayList<>());
        }
        named.query("SELECT order_id, product_id, quantity, unit_price FROM order_items "
                        + "WHERE order_id IN (:ids) ORDER BY order_id, line_no",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    byOrder.get(rs.getLong("order_id")).add(new Order.Item(rs.getLong("product_id"),
                            rs.getLong("quantity"), rs.getLong("unit_price")));
                });
        return orders.stream().map(o -> o.withItems(List.copyOf(byOrder.get(o.id())))).toList();
    }
}
