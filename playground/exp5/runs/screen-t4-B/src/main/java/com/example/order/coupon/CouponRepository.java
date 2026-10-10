package com.example.order.coupon;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Coupon codes are unique per tenant only (M3.1); every query is scoped by tenant. */
@Repository
public class CouponRepository {

    private static final String COLS = "code, type, value, min_order_amount, max_discount_amount, total_quantity, "
            + "used_count, valid_from, valid_until";

    private static final RowMapper<Coupon> MAPPER = (rs, i) -> new Coupon(
            rs.getString("code"), rs.getString("type"), rs.getLong("value"), rs.getLong("min_order_amount"),
            (Long) rs.getObject("max_discount_amount", Long.class), rs.getLong("total_quantity"),
            rs.getLong("used_count"), rs.getObject("valid_from", OffsetDateTime.class),
            rs.getObject("valid_until", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public CouponRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the tenant already has this code */
    public boolean insert(String tenantId, Coupon c) {
        int n = jdbc.update("INSERT INTO coupons (tenant_id, " + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?) "
                        + "ON CONFLICT (tenant_id, code) DO NOTHING",
                tenantId, c.code(), c.type(), c.value(), c.minOrderAmount(), c.maxDiscountAmount(),
                c.totalQuantity(), c.validFrom(), c.validUntil());
        return n == 1;
    }

    public Optional<Coupon> find(String tenantId, String code) {
        List<Coupon> rows = jdbc.query("SELECT " + COLS + " FROM coupons WHERE tenant_id = ? AND code = ?",
                MAPPER, tenantId, code);
        return rows.stream().findFirst();
    }

    public Coupon lock(String tenantId, String code) {
        return jdbc.queryForObject("SELECT " + COLS + " FROM coupons WHERE tenant_id = ? AND code = ? FOR UPDATE",
                MAPPER, tenantId, code);
    }

    public void addUsed(String tenantId, String code, long delta) {
        jdbc.update("UPDATE coupons SET used_count = used_count + ? WHERE tenant_id = ? AND code = ?",
                delta, tenantId, code);
    }
}
