package com.example.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트가 공유하는 PostgreSQL Testcontainer (JVM 당 1개).
 * 컨테이너를 빈으로 등록(@ServiceConnection)하면 Spring 이 컨텍스트 종료 시 컨테이너를 같이 멈춘다.
 * {@code @DirtiesContext} 로 컨텍스트를 닫는 테스트가 있으면 다른 컨텍스트의 DB 가 사라지므로,
 * 컨테이너는 정적으로 직접 띄우고 접속 정보만 {@link DynamicPropertyRegistrar} 로 주입한다.
 * 테스트 클래스에서 {@code @Import(PostgresTestConfig.class)} 하거나 {@link IntegrationTestBase} 를 상속한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("orders")
                    .withUsername("orders")
                    .withPassword("orders")
                    .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    @Bean
    public DynamicPropertyRegistrar postgresConnectionProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        };
    }
}
