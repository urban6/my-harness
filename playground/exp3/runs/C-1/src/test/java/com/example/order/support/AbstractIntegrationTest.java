package com.example.order.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base: one PostgreSQL Testcontainer + one PG stub per JVM, full Spring context, MutableClock,
 * sweep scheduler disabled (tests call the sweeper explicitly). Tables are truncated before each test.
 */
@SpringBootTest(properties = {
        "order-payment.expiry.sweep-enabled=false",
        "order-payment.gateway.connect-timeout=PT1S",
        "order-payment.gateway.read-timeout=PT1S"
})
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final PaymentGatewayStub GATEWAY = new PaymentGatewayStub();

    static {
        POSTGRES.start();
        GATEWAY.start();
        Runtime.getRuntime().addShutdownHook(new Thread(GATEWAY::stop));
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("order-payment.gateway.url", GATEWAY::baseUrl);
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper objectMapper;

    protected ApiClient api;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE TABLE payments, order_items, orders, coupons, products RESTART IDENTITY CASCADE");
        GATEWAY.reset();
        clock.reset();
        api = new ApiClient(mvc, objectMapper);
    }

    protected int reserved(long productId) {
        return jdbc.queryForObject("SELECT reserved FROM products WHERE id = ?", Integer.class, productId);
    }

    protected int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    protected int couponUsed(String code) {
        return jdbc.queryForObject("SELECT used_count FROM coupons WHERE code = ?", Integer.class, code);
    }
}
