package com.example.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 PostgreSQL 컨테이너. Spring 컨텍스트가 라이프사이클을 관리하므로
 * 같은 컨텍스트를 공유하는 테스트 클래스들이 컨테이너 하나를 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
