package com.example.order.point;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PointRepository {

    private final JdbcTemplate jdbc;

    public PointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Atomically adds points and returns the new balance. */
    public long grant(String userId, long amount) {
        Long balance = jdbc.queryForObject("INSERT INTO point_accounts (user_id, balance) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET balance = point_accounts.balance + EXCLUDED.balance "
                + "RETURNING balance", Long.class, userId, amount);
        return balance;
    }

    /** Never-granted users have no row and a balance of 0. */
    public long balance(String userId) {
        return first("SELECT balance FROM point_accounts WHERE user_id = ?", userId);
    }

    /**
     * Row lock held until the end of the transaction; always the last lock taken (after orders, products, coupon).
     */
    public long lockBalance(String userId) {
        return first("SELECT balance FROM point_accounts WHERE user_id = ? FOR UPDATE", userId);
    }

    /** Gives points back (P2.6, P4.6). */
    public void add(String userId, long amount) {
        grant(userId, amount);
    }

    /**
     * Spends points. The caller holds the row lock from {@link #lockBalance} and has checked the balance; this is a
     * plain UPDATE because an upsert would be rejected by the balance CHECK on its (negative) insert row.
     */
    public void deduct(String userId, long amount) {
        jdbc.update("UPDATE point_accounts SET balance = balance - ? WHERE user_id = ?", amount, userId);
    }

    private long first(String sql, String userId) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }
}
