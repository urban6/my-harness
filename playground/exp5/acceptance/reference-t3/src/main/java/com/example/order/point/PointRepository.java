package com.example.order.point;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** P1: one row per user; a missing row means balance 0. The CHECK (balance >= 0) is the last line of defence. */
@Repository
public class PointRepository {

    private final JdbcTemplate jdbc;

    public PointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long balance(String userId) {
        List<Long> rows = jdbc.queryForList("SELECT balance FROM point_accounts WHERE user_id = ?", Long.class,
                userId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** Row lock on the account (if any); callers lock it after products and the coupon. */
    public long lockBalance(String userId) {
        List<Long> rows = jdbc.queryForList("SELECT balance FROM point_accounts WHERE user_id = ? FOR UPDATE",
                Long.class, userId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** Atomically adds {@code amount} (> 0), creating the account if needed; returns the new balance. */
    public long add(String userId, long amount) {
        return jdbc.queryForObject("INSERT INTO point_accounts (user_id, balance) VALUES (?, ?) "
                        + "ON CONFLICT (user_id) DO UPDATE SET balance = point_accounts.balance + EXCLUDED.balance "
                        + "RETURNING balance",
                Long.class, userId, amount);
    }

    /** Caller must hold the row lock and have checked the balance. */
    public void deduct(String userId, long amount) {
        jdbc.update("UPDATE point_accounts SET balance = balance - ? WHERE user_id = ?", amount, userId);
    }
}
