package com.example.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** JVM-wide singleton PostgreSQL container (started once, reused by every Spring context in the test run). */
public final class Containers {

    private static PostgreSQLContainer<?> instance;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (instance == null) {
            instance = new PostgreSQLContainer<>("postgres:16-alpine");
            instance.start();
        }
        return instance;
    }

    public static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> pg) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }
}
