package com.example.order.point;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Spendable points per user. A user without a row has a balance of 0. */
@Repository
public class PointRepository {

    private final JdbcTemplate jdbc;

    public PointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long find(String userId) {
        return jdbc.queryForList("SELECT balance FROM point_accounts WHERE user_id = ?", Long.class, userId)
                .stream().findFirst().orElse(0L);
    }

    /** Locks the balance row for the rest of the transaction (a missing row reads as 0). */
    public long lockBalance(String userId) {
        return jdbc.queryForList("SELECT balance FROM point_accounts WHERE user_id = ? FOR UPDATE", Long.class, userId)
                .stream().findFirst().orElse(0L);
    }

    /** Adds to the balance, creating the row when needed; returns the new balance. */
    public long add(String userId, long amount) {
        Long balance = jdbc.queryForObject("INSERT INTO point_accounts (user_id, balance) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET balance = point_accounts.balance + EXCLUDED.balance "
                + "RETURNING balance", Long.class, userId, amount);
        return balance;
    }

    /** Spends points. The caller holds the balance lock and has checked that the balance covers amount. */
    public void spend(String userId, long amount) {
        jdbc.update("UPDATE point_accounts SET balance = balance - ? WHERE user_id = ?", amount, userId);
    }
}
