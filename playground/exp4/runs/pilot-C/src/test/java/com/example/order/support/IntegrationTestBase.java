package com.example.order.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 통합 테스트 공통 베이스: 실제 서버(랜덤 포트) + Testcontainers PostgreSQL + 가짜 PG.
 * 공통 헬퍼는 {@link ApiTestSupport} 에 있다.
 * 설정(ttl 등)을 바꿔야 하는 테스트는 같은 방식으로 별도 베이스/서브클래스에서
 * {@code @DynamicPropertySource} 로 {@code order.payment.ttl}, {@code order.expiry.scan-interval} 을 덮어쓴다.
 * 컨텍스트를 따로 만드는 클래스는 DB 커넥션 고갈을 막기 위해 {@code @DirtiesContext(AFTER_CLASS)} 를 붙인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
public abstract class IntegrationTestBase extends ApiTestSupport {

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", PG::baseUrl);
    }
}
