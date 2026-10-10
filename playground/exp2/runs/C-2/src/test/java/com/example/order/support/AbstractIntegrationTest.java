package com.example.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 기본 통합 테스트 베이스: 가짜 PG 연결, 결제 대기 TTL 15분, 만료 스케줄러 동작.
 * 이 클래스를 상속한 테스트는 모두 하나의 Spring 컨텍스트를 공유한다.
 */
public abstract class AbstractIntegrationTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", PG::baseUrl);
        registry.add("order.payment-ttl", () -> "PT15M");
    }
}
