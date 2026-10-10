package com.example.order.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the invariants of 02_db_design.md section 5 directly against the database. */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    /** @return human-readable violations; empty when every invariant holds. */
    public static List<String> violations(JdbcTemplate jdbc) {
        List<String> out = new ArrayList<>();

        // products: 0 <= reserved <= stock
        jdbc.queryForList("SELECT id, stock, reserved FROM products WHERE reserved < 0 OR reserved > stock OR stock < 0")
                .forEach(r -> out.add("product bounds violated: " + r));

        // coupons: 0 <= used_count <= total_quantity
        jdbc.queryForList("SELECT id, code, used_count, total_quantity FROM coupons WHERE used_count < 0 OR used_count > total_quantity")
                .forEach(r -> out.add("coupon bounds violated: " + r));

        // coupons: used_count == active orders using it (PENDING_PAYMENT, PAID, SHIPPED, DELIVERED)
        jdbc.queryForList("""
                SELECT c.code, c.used_count,
                       (SELECT count(*) FROM orders o WHERE o.coupon_id = c.id
                         AND o.status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED')) AS active_orders
                  FROM coupons c
                 WHERE c.used_count <> (SELECT count(*) FROM orders o WHERE o.coupon_id = c.id
                         AND o.status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED'))
                """).forEach(r -> out.add("coupon used_count mismatch: " + r));

        // products: reserved == sum(quantity) of PENDING_PAYMENT and PAID orders
        jdbc.queryForList("""
                SELECT p.id, p.reserved,
                       COALESCE((SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id = i.order_id
                                  WHERE i.product_id = p.id AND o.status IN ('PENDING_PAYMENT','PAID')), 0) AS expected
                  FROM products p
                 WHERE p.reserved <> COALESCE((SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id = i.order_id
                                  WHERE i.product_id = p.id AND o.status IN ('PENDING_PAYMENT','PAID')), 0)
                """).forEach(r -> out.add("product reserved mismatch: " + r));

        // payments: APPROVED -> order PAID/SHIPPED/DELIVERED ; REFUNDED -> order REFUNDED
        jdbc.queryForList("""
                SELECT p.order_id, p.status AS payment_status, o.status AS order_status
                  FROM payments p JOIN orders o ON o.id = p.order_id
                 WHERE (p.status = 'APPROVED' AND o.status NOT IN ('PAID','SHIPPED','DELIVERED'))
                    OR (p.status = 'REFUNDED' AND o.status <> 'REFUNDED')
                """).forEach(r -> out.add("payment/order status mismatch: " + r));

        // at most one payment per order, at most one order per (user, key)
        jdbc.queryForList("SELECT order_id, count(*) FROM payments GROUP BY order_id HAVING count(*) > 1")
                .forEach(r -> out.add("multiple payments for one order: " + r));
        jdbc.queryForList("SELECT user_id, idempotency_key, count(*) FROM orders GROUP BY user_id, idempotency_key HAVING count(*) > 1")
                .forEach(r -> out.add("duplicate (user,key) orders: " + r));

        // no lease may be left behind once all requests finished
        jdbc.queryForList("SELECT id, lease_kind FROM orders WHERE lease_token IS NOT NULL")
                .forEach(r -> out.add("dangling lease: " + r));
        return out;
    }
}
