package com.example.order.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for the Phase 3 integration tests: one PostgreSQL Testcontainer + one programmable PG stub for the whole
 * JVM, scheduler off (deterministic), PG read timeout 1s so timeout scenarios stay fast. Tests share the database, so
 * every test creates its own uniquely named data and never assumes empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "order.expiry-sweep-enabled=false", "payment.gateway.read-timeout=PT1S" })
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES = Containers.postgres();
    protected static final PgStub PG = new PgStub();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.register(registry, POSTGRES);
        registry.add("payment.gateway.url", PG::url);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ApiClient api;

    @BeforeEach
    void initBase() {
        api = new ApiClient(rest);
        PG.reset();
    }

    protected String orderStatusInDb(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }
}
