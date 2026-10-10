package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * R18: the Flyway-built schema itself rejects invalid data. Statements go straight to PostgreSQL through
 * JdbcTemplate, bypassing every application-level check, so a failure here proves the constraint is in the database.
 */
class SchemaConstraintsTest extends AbstractIntegrationTest {

    @Autowired
    Environment environment;

    private void assertRejected(String constraint, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .as("expected %s to reject: %s", constraint, sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private long insertProduct(int stock, int reserved) {
        return jdbc.queryForObject("""
                INSERT INTO products (name, price, stock, reserved, created_at, updated_at)
                VALUES ('p', 100, ?, ?, now(), now()) RETURNING id""", Long.class, stock, reserved);
    }

    private static final String COUPON_INSERT = """
            INSERT INTO coupons (code, type, value, min_order_amount, max_discount_amount, total_quantity, used_count,
                                 valid_from, valid_until, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, now())""";

    private void insertCoupon(String code, String type, long value, long min, Long max, int total, int used, String from, String until) {
        jdbc.update(COUPON_INSERT, code, type, value, min, max, total, used, from, until);
    }

    private long insertOrder(String user, String key, String status) {
        return jdbc.queryForObject("""
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash,
                                    created_at, expires_at, updated_at, paid_at)
                VALUES (?, ?, 100, 0, 100, ?, 'h', now(), now(), now(), CASE WHEN ? IN ('PAID','SHIPPED','DELIVERED','REFUNDED') THEN now() END)
                RETURNING id""", Long.class, user, status, key, status);
    }

    // ------------------------------------------------------------------ migrations

    @Test
    @DisplayName("R18 Flyway applied V1, V2 and V3 successfully and Hibernate runs with ddl-auto=validate")
    void flywayHistory_andDdlAuto() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).extracting(r -> r.get("version")).containsExactly("1", "2", "3");
        assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isIn("validate", "none");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("products", "coupons", "orders", "order_items", "payments");
    }

    @Test
    @DisplayName("R18 money columns are BIGINT (integer won), quantities INTEGER, timestamps TIMESTAMPTZ")
    void columnTypes() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("products.price", "bigint"), Map.entry("products.stock", "integer"), Map.entry("products.reserved", "integer"),
                Map.entry("coupons.value", "bigint"), Map.entry("coupons.min_order_amount", "bigint"),
                Map.entry("coupons.max_discount_amount", "bigint"), Map.entry("coupons.total_quantity", "integer"),
                Map.entry("coupons.valid_from", "timestamp with time zone"), Map.entry("coupons.valid_until", "timestamp with time zone"),
                Map.entry("orders.subtotal", "bigint"), Map.entry("orders.discount", "bigint"), Map.entry("orders.total_price", "bigint"),
                Map.entry("orders.created_at", "timestamp with time zone"), Map.entry("orders.expires_at", "timestamp with time zone"),
                Map.entry("orders.paid_at", "timestamp with time zone"),
                Map.entry("order_items.unit_price", "bigint"), Map.entry("order_items.quantity", "integer"),
                Map.entry("payments.amount", "bigint"));
        expected.forEach((column, type) -> {
            String[] parts = column.split("\\.");
            assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?",
                    String.class, parts[0], parts[1])).as(column).isEqualTo(type);
        });
    }

    @Test
    @DisplayName("R18 the cursor/filter indexes exist: (user_id,status,id DESC), (user_id,id DESC), (status,id DESC) and the pending-expiry index")
    void indexesExist() {
        Map<String, String> defs = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='orders'")
                .forEach(r -> defs.put((String) r.get("indexname"), (String) r.get("indexdef")));
        assertThat(defs.get("idx_orders_user_status_id")).contains("(user_id, status, id DESC)");
        assertThat(defs.get("idx_orders_user_id")).contains("(user_id, id DESC)");
        assertThat(defs.get("idx_orders_status_id")).contains("(status, id DESC)");
        assertThat(defs.get("idx_orders_pending_expiry")).contains("(expires_at)").contains("PENDING_PAYMENT");
        assertThat(defs).containsKey("uk_orders_user_idem");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='idx_order_items_product'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("R18 the cursor query shape (user_id, status, id < cursor ORDER BY id DESC LIMIT n) is served by an index scan without a sort")
    void cursorQueryUsesIndexWithoutSort() {
        // many rows + ANALYZE so the planner has real statistics (enable_seqscan is deliberately NOT disabled)
        jdbc.execute("""
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, paid_at)
                SELECT 'u' || (g % 5), CASE WHEN g % 2 = 0 THEN 'PAID' ELSE 'PENDING_PAYMENT' END, 100, 0, 100, 'k' || g, 'h', now(), now(), now(),
                       CASE WHEN g % 2 = 0 THEN now() END
                  FROM generate_series(1, 20000) g""");
        jdbc.execute("ANALYZE orders");

        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id FROM orders WHERE user_id = 'u1' AND status = 'PAID' AND id < 15000 ORDER BY id DESC LIMIT 21", String.class));
        assertThat(plan).contains("Index").doesNotContain("Seq Scan").doesNotContain("Sort");
    }

    // ------------------------------------------------------------------ products

    @Test
    @DisplayName("R18 products: CHECK reserved <= stock, reserved >= 0, stock >= 0, price >= 0 reject bad rows and bad updates")
    void productChecks() {
        assertRejected("ck_products_reserved", "INSERT INTO products (name, price, stock, reserved, created_at, updated_at) VALUES ('p', 1, 5, 6, now(), now())");
        assertRejected("ck_products_reserved", "INSERT INTO products (name, price, stock, reserved, created_at, updated_at) VALUES ('p', 1, 5, -1, now(), now())");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO products (name, price, stock, reserved, created_at, updated_at) VALUES ('p', 1, -1, 0, now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_products_");
        assertRejected("ck_products_price", "INSERT INTO products (name, price, stock, reserved, created_at, updated_at) VALUES ('p', -1, 5, 0, now(), now())");

        long full = insertProduct(5, 5);
        assertRejected("ck_products_reserved", "UPDATE products SET reserved = reserved + 1 WHERE id = ?", full);
        assertRejected("ck_products_reserved", "UPDATE products SET stock = stock - 1 WHERE id = ?", full);
        assertThatCode(() -> jdbc.update("UPDATE products SET stock = stock - 5, reserved = reserved - 5 WHERE id = ?", full)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ coupons

    @Test
    @DisplayName("R18 coupons: UNIQUE code, type/value/quantity/period CHECKs, used_count <= total_quantity")
    void couponConstraints() {
        String from = "2030-01-01T00:00:00Z";
        String until = "2030-02-01T00:00:00Z";
        insertCoupon("OK", "FIXED", 100, 0, null, 5, 0, from, until);
        assertRejected("uk_coupons_code", COUPON_INSERT, "OK", "FIXED", 100L, 0L, null, 5, 0, from, until);
        assertRejected("ck_coupons_type", COUPON_INSERT, "T", "BOGUS", 100L, 0L, null, 5, 0, from, until);
        assertRejected("ck_coupons_value", COUPON_INSERT, "R101", "RATE", 101L, 0L, null, 5, 0, from, until);
        assertRejected("ck_coupons_value", COUPON_INSERT, "V0", "FIXED", 0L, 0L, null, 5, 0, from, until);
        assertRejected("ck_coupons_min_order", COUPON_INSERT, "MIN", "FIXED", 1L, -1L, null, 5, 0, from, until);
        assertRejected("ck_coupons_max_disc", COUPON_INSERT, "MAX", "FIXED", 1L, 0L, -1L, 5, 0, from, until);
        assertRejected("ck_coupons_total", COUPON_INSERT, "TOT", "FIXED", 1L, 0L, null, 0, 0, from, until);
        assertRejected("ck_coupons_used", COUPON_INSERT, "USED", "FIXED", 1L, 0L, null, 5, 6, from, until);
        assertRejected("ck_coupons_period", COUPON_INSERT, "PER", "FIXED", 1L, 0L, null, 5, 0, until, from);
        assertRejected("ck_coupons_period", COUPON_INSERT, "SAME", "FIXED", 1L, 0L, null, 5, 0, from, from);
        assertRejected("ck_coupons_used", "UPDATE coupons SET used_count = total_quantity + 1 WHERE code = 'OK'");
        assertRejected("ck_coupons_used", "UPDATE coupons SET used_count = -1 WHERE code = 'OK'");
        assertThatCode(() -> insertCoupon("R100", "RATE", 100, 0, 0L, 1, 1, from, until)).doesNotThrowAnyException();
        assertThatCode(() -> insertCoupon("F-BIG", "FIXED", 5_000_000_000L, 0, null, 1, 0, from, until)).doesNotThrowAnyException(); // BIGINT
    }

    // ------------------------------------------------------------------ orders / items

    @Test
    @DisplayName("R18 orders: UNIQUE(user_id, idempotency_key) is per user, status CHECK, amount arithmetic CHECK")
    void orderConstraints() {
        insertOrder("alice", "k1", "PENDING_PAYMENT");
        assertRejected("uk_orders_user_idem", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at)
                VALUES ('alice', 'PENDING_PAYMENT', 1, 0, 1, 'k1', 'h', now(), now(), now())""");
        assertThatCode(() -> insertOrder("bob", "k1", "PENDING_PAYMENT")).doesNotThrowAnyException();
        assertThatCode(() -> insertOrder("alice", "k2", "PENDING_PAYMENT")).doesNotThrowAnyException();

        assertRejected("ck_orders_status", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at)
                VALUES ('x', 'WHATEVER', 1, 0, 1, 'bad-status', 'h', now(), now(), now())""");
        assertRejected("ck_orders_amounts", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at)
                VALUES ('x', 'PENDING_PAYMENT', 100, 10, 100, 'bad-total', 'h', now(), now(), now())""");
        assertRejected("ck_orders_amounts", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at)
                VALUES ('x', 'PENDING_PAYMENT', 100, 200, -100, 'bad-discount', 'h', now(), now(), now())""");
    }

    @Test
    @DisplayName("R18 orders: coupon id/code must come as a pair, paid statuses need paid_at, a lease must be all-or-nothing")
    void orderConsistencyChecks() {
        assertRejected("ck_orders_coupon_pair", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, coupon_code)
                VALUES ('x', 'PENDING_PAYMENT', 100, 0, 100, 'cp', 'h', now(), now(), now(), 'GHOST')""");
        for (String paidStatus : new String[] {"PAID", "SHIPPED", "DELIVERED", "REFUNDED"}) {
            assertRejected("ck_orders_paid_at", """
                    INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at)
                    VALUES ('x', '%s', 100, 0, 100, 'pa-%s', 'h', now(), now(), now())""".formatted(paidStatus, paidStatus));
        }
        assertRejected("ck_orders_lease", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, lease_kind)
                VALUES ('x', 'PENDING_PAYMENT', 100, 0, 100, 'l1', 'h', now(), now(), now(), 'PAY')""");
        assertRejected("ck_orders_lease", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, lease_kind, lease_token, lease_expires_at)
                VALUES ('x', 'PENDING_PAYMENT', 100, 0, 100, 'l2', 'h', now(), now(), now(), 'OTHER', gen_random_uuid(), now())""");
        long pending = insertOrder("x", "mv", "PENDING_PAYMENT");
        assertRejected("ck_orders_paid_at", "UPDATE orders SET status = 'PAID' WHERE id = ?", pending);
    }

    @Test
    @DisplayName("R18 order_items: quantity > 0, unit_price >= 0, one line per product and per line_no, FKs to orders and products")
    void orderItemConstraints() {
        long product = insertProduct(10, 0);
        long order = insertOrder("u", "k", "PENDING_PAYMENT");
        String insert = "INSERT INTO order_items (order_id, line_no, product_id, quantity, unit_price) VALUES (?, ?, ?, ?, ?)";
        assertThatCode(() -> jdbc.update(insert, order, 1, product, 1, 100)).doesNotThrowAnyException();
        assertRejected("ck_order_items_quantity", insert, order, 2, product, 0, 100);
        assertRejected("ck_order_items_unit_price", insert, order, 2, product, 1, -1);
        assertRejected("uk_order_items_order_product", insert, order, 2, product, 1, 100);
        long other = insertProduct(10, 0);
        assertRejected("uk_order_items_order_line", insert, order, 1, other, 1, 100);
        assertRejected("fk_order_items_product", insert, order, 3, 987654321L, 1, 100);
        assertRejected("fk_order_items_order", insert, 987654321L, 1, product, 1, 100);
        assertRejected("fk_order_items_product", "DELETE FROM products WHERE id = ?", product);
        assertRejected("fk_order_items_order", "DELETE FROM orders WHERE id = ?", order);
    }

    @Test
    @DisplayName("R18 orders.coupon_id references coupons: an unknown coupon id is rejected, a used coupon cannot be deleted")
    void orderCouponForeignKey() {
        assertRejected("fk_orders_coupon", """
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, coupon_id, coupon_code)
                VALUES ('x', 'PENDING_PAYMENT', 100, 0, 100, 'fk', 'h', now(), now(), now(), 424242, 'GHOST')""");
        insertCoupon("REAL", "FIXED", 10, 0, null, 3, 0, "2030-01-01T00:00:00Z", "2030-02-01T00:00:00Z");
        long couponId = jdbc.queryForObject("SELECT id FROM coupons WHERE code = 'REAL'", Long.class);
        jdbc.update("""
                INSERT INTO orders (user_id, status, subtotal, discount, total_price, idempotency_key, request_hash, created_at, expires_at, updated_at, coupon_id, coupon_code)
                VALUES ('x', 'PENDING_PAYMENT', 100, 10, 90, 'fk2', 'h', now(), now(), now(), ?, 'REAL')""", couponId);
        assertRejected("fk_orders_coupon", "DELETE FROM coupons WHERE id = ?", couponId);
    }

    // ------------------------------------------------------------------ payments

    @Test
    @DisplayName("R18 payments: one row per order, status/amount/attempt CHECKs, APPROVED needs a PG id, PG ids are unique")
    void paymentConstraints() {
        long order = insertOrder("u", "k", "PENDING_PAYMENT");
        long order2 = insertOrder("u", "k2", "PENDING_PAYMENT");
        String insert = """
                INSERT INTO payments (order_id, idempotency_key, request_hash, amount, status, pg_payment_id, attempt_count, created_at, updated_at)
                VALUES (?, 'pk', 'h', ?, ?, ?, ?, now(), now())""";
        assertThatCode(() -> jdbc.update(insert, order, 100, "INITIATED", null, 1)).doesNotThrowAnyException();
        assertRejected("uk_payments_order", insert, order, 100, "INITIATED", null, 1);
        assertRejected("ck_payments_status", insert, order2, 100, "WEIRD", null, 1);
        assertRejected("ck_payments_amount", insert, order2, -1, "INITIATED", null, 1);
        assertRejected("ck_payments_attempt", insert, order2, 100, "INITIATED", null, 0);
        assertRejected("ck_payments_pgid", insert, order2, 100, "APPROVED", null, 1);
        assertRejected("ck_payments_pgid", insert, order2, 100, "REFUNDED", null, 1);
        assertRejected("fk_payments_order", insert, 987654321L, 100, "INITIATED", null, 1);
        assertThatCode(() -> jdbc.update("UPDATE payments SET status = 'APPROVED', pg_payment_id = 'pg-1' WHERE order_id = ?", order)).doesNotThrowAnyException();
        assertRejected("uk_payments_pg_payment_id", insert, order2, 100, "APPROVED", "pg-1", 1);
    }
}
