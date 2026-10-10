package com.example.order.product;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Every query is scoped by tenant: another tenant's product behaves as if it did not exist (M2.2). */
@Repository
public class ProductRepository {

    private static final RowMapper<Product> MAPPER = (rs, i) -> new Product(
            rs.getLong("id"), rs.getString("name"), rs.getLong("price"), rs.getLong("stock"), rs.getLong("reserved"));

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Product insert(String tenantId, String name, long price, long stock) {
        return jdbc.queryForObject(
                "INSERT INTO products (tenant_id, name, price, stock, reserved) VALUES (?, ?, ?, ?, 0) "
                        + "RETURNING id, name, price, stock, reserved",
                MAPPER, tenantId, name, price, stock);
    }

    public Optional<Product> find(String tenantId, long id) {
        List<Product> rows = jdbc.query(
                "SELECT id, name, price, stock, reserved FROM products WHERE tenant_id = ? AND id = ?",
                MAPPER, tenantId, id);
        return rows.stream().findFirst();
    }

    public boolean exists(String tenantId, long id) {
        Boolean b = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM products WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, id);
        return Boolean.TRUE.equals(b);
    }

    /** Row lock; callers must lock several products in ascending id order. */
    public Product lock(String tenantId, long id) {
        return jdbc.queryForObject(
                "SELECT id, name, price, stock, reserved FROM products WHERE tenant_id = ? AND id = ? FOR UPDATE",
                MAPPER, tenantId, id);
    }

    public void addReserved(String tenantId, long id, long delta) {
        jdbc.update("UPDATE products SET reserved = reserved + ? WHERE tenant_id = ? AND id = ?",
                delta, tenantId, id);
    }

    /** Payment approved: the reserved units are sold. */
    public void consumeReserved(String tenantId, long id, long qty) {
        jdbc.update("UPDATE products SET stock = stock - ?, reserved = reserved - ? WHERE tenant_id = ? AND id = ?",
                qty, qty, tenantId, id);
    }

    public void addStock(String tenantId, long id, long qty) {
        jdbc.update("UPDATE products SET stock = stock + ? WHERE tenant_id = ? AND id = ?", qty, tenantId, id);
    }
}
