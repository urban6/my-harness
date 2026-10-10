package com.example.order.product;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private static final RowMapper<Product> MAPPER = (rs, i) -> new Product(
            rs.getLong("id"), rs.getString("name"), rs.getLong("price"), rs.getLong("stock"), rs.getLong("reserved"));

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Product insert(String name, long price, long stock) {
        return jdbc.queryForObject(
                "INSERT INTO products (name, price, stock, reserved) VALUES (?, ?, ?, 0) "
                        + "RETURNING id, name, price, stock, reserved",
                MAPPER, name, price, stock);
    }

    public Optional<Product> find(long id) {
        List<Product> rows = jdbc.query("SELECT id, name, price, stock, reserved FROM products WHERE id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean exists(long id) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM products WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(b);
    }

    /** Row lock; callers must lock several products in ascending id order. */
    public Product lock(long id) {
        return jdbc.queryForObject(
                "SELECT id, name, price, stock, reserved FROM products WHERE id = ? FOR UPDATE", MAPPER, id);
    }

    public void addReserved(long id, long delta) {
        jdbc.update("UPDATE products SET reserved = reserved + ? WHERE id = ?", delta, id);
    }

    /** Payment approved: the reserved units are sold. */
    public void consumeReserved(long id, long qty) {
        jdbc.update("UPDATE products SET stock = stock - ?, reserved = reserved - ? WHERE id = ?", qty, qty, id);
    }

    public void addStock(long id, long qty) {
        jdbc.update("UPDATE products SET stock = stock + ? WHERE id = ?", qty, id);
    }
}
