package com.example.order.coupon;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    /** @return false when the code already exists */
    public boolean insert(Coupon c) {
        int n = jdbc.update("INSERT INTO coupons (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?) "
                        + "ON CONFLICT (code) DO NOTHING",
                c.code(), c.type(), c.value(), c.minOrderAmount(), c.maxDiscountAmount(), c.totalQuantity(),
                c.validFrom(), c.validUntil());
        return n == 1;
    }

    public Optional<Coupon> find(String code) {
        List<Coupon> rows = jdbc.query("SELECT " + COLS + " FROM coupons WHERE code = ?", MAPPER, code);
        return rows.stream().findFirst();
    }

    public Coupon lock(String code) {
        return jdbc.queryForObject("SELECT " + COLS + " FROM coupons WHERE code = ? FOR UPDATE", MAPPER, code);
    }

    public void addUsed(String code, long delta) {
        jdbc.update("UPDATE coupons SET used_count = used_count + ? WHERE code = ?", delta, code);
    }
}
