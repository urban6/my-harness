package com.example.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너. @ServiceConnection이 spring.datasource.*를 자동 주입한다.
 * Flyway V1이 실제로 적용되고 Hibernate가 validate 한다(ddl-auto를 바꾸지 않는다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
