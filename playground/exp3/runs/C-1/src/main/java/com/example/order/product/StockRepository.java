package com.example.order.product;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import com.example.order.common.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Atomic conditional UPDATEs on products (02_db_design.md section 3 patterns 1-3). No SELECT-then-UPDATE.
 */
@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** id -> current price for the ids that exist. */
    public Map<Long, Long> findPrices(Collection<Long> ids) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.sql("SELECT id, price FROM products WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    result.put(rs.getLong("id"), rs.getLong("price"));
                    return null;
                }).list();
        return result;
    }

    /** (1) reserve; false when stock - reserved < quantity. */
    public boolean reserve(long productId, int quantity, Instant now) {
        return jdbc.sql("UPDATE products SET reserved = reserved + :q, updated_at = :now "
                        + "WHERE id = :id AND stock - reserved >= :q")
                .param("q", quantity).param("now", Times.ts(now)).param("id", productId)
                .update() == 1;
    }

    /** (2) release a reservation; false means the invariant was violated (caller must fail the transaction). */
    public boolean release(long productId, int quantity, Instant now) {
        return jdbc.sql("UPDATE products SET reserved = reserved - :q, updated_at = :now "
                        + "WHERE id = :id AND reserved >= :q")
                .param("q", quantity).param("now", Times.ts(now)).param("id", productId)
                .update() == 1;
    }

    /** (3) ship: stock and reserved are decremented in one statement. */
    public boolean commitShip(long productId, int quantity, Instant now) {
        return jdbc.sql("UPDATE products SET stock = stock - :q, reserved = reserved - :q, updated_at = :now "
                        + "WHERE id = :id AND reserved >= :q AND stock >= :q")
                .param("q", quantity).param("now", Times.ts(now)).param("id", productId)
                .update() == 1;
    }

    public int available(long productId) {
        return jdbc.sql("SELECT stock - reserved FROM products WHERE id = :id")
                .param("id", productId).query(Integer.class).optional().orElse(0);
    }
}
