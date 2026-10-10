package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.common.config.OrderProperties;
import com.example.order.common.config.PaymentGatewayProperties;
import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * C4. 런타임 설정은 환경 변수로 덮어쓸 수 있어야 한다.
 *
 * <ul>
 *   <li>통합: 환경 변수와 같은 이름(PAYMENT_GATEWAY_URL, ORDER_PAYMENT_TTL)의 프로퍼티를 주입해 application.yml의
 *       플레이스홀더가 실제 동작(PG 호출 대상, 주문 만료 시각)에 반영되는지 본다. 이 클래스의 컨텍스트에서
 *       {@code payment.gateway.url}·{@code order.payment-ttl}은 직접 지정하지 않는다.</li>
 *   <li>단위: 진짜 application.yml을 읽고, 환경 변수 소스(SystemEnvironmentPropertySource)를 앞에 두어
 *       SERVER_PORT · SPRING_DATASOURCE_* · PAYMENT_GATEWAY_URL · ORDER_PAYMENT_TTL의 덮어쓰기와 기본값을 본다.</li>
 * </ul>
 */
class C04ConfigOverrideTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void environmentStyleProperties(DynamicPropertyRegistry registry) {
        registry.add("PAYMENT_GATEWAY_URL", PG::baseUrl);
        registry.add("ORDER_PAYMENT_TTL", () -> "PT7M30S");
    }

    @Autowired
    OrderProperties orderProperties;

    @Autowired
    PaymentGatewayProperties gatewayProperties;

    @Test
    @DisplayName("C4 ORDER_PAYMENT_TTL이 결제 대기 TTL로 반영된다 (expiresAt = createdAt + PT7M30S)")
    void c4_orderPaymentTtl_overridesDefault() {
        ApiResponse created = placeOrderOk(newProduct(1_000, 5), 1);

        assertThat(orderProperties.paymentTtl()).isEqualTo(Duration.ofMinutes(7).plusSeconds(30));
        assertThat(Duration.between(instant(created, "createdAt"), instant(created, "expiresAt")))
                .isEqualTo(Duration.ofMinutes(7).plusSeconds(30));
    }

    @Test
    @DisplayName("C4 PAYMENT_GATEWAY_URL이 PG 호출 대상으로 반영된다 (가짜 PG가 결제 요청을 받는다)")
    void c4_paymentGatewayUrl_overridesDefault() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();

        ApiResponse paid = pay(orderId);

        assertThat(gatewayProperties.url().toString()).isEqualTo(PG.baseUrl());
        assertThat(paid.status()).isEqualTo(200);
        assertThat(PG.paymentCallsForOrder(orderId)).hasSize(1);
    }

    // ------------------------------------------------------------------ 단위: application.yml 플레이스홀더

    private static Binder binderWithEnv(Map<String, Object> env) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new HashMap<>(env)));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        yaml.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    @DisplayName("C4 환경 변수가 없으면 기본값을 쓴다 (SERVER_PORT=8080, ORDER_PAYMENT_TTL=PT15M)")
    void c4_defaults_whenEnvIsAbsent() throws Exception {
        Binder binder = binderWithEnv(Map.of());

        assertThat(binder.bind("server.port", Integer.class).get()).isEqualTo(8080);
        assertThat(binder.bind("order.payment-ttl", Duration.class).get()).isEqualTo(Duration.ofMinutes(15));
        assertThat(binder.bind("payment.gateway.url", String.class).isBound()).isTrue();
    }

    @Test
    @DisplayName("C4 환경 변수 SERVER_PORT · PAYMENT_GATEWAY_URL · ORDER_PAYMENT_TTL이 application.yml 값을 덮어쓴다")
    void c4_envVariables_overrideYamlPlaceholders() throws Exception {
        Binder binder = binderWithEnv(Map.of(
                "SERVER_PORT", "18080",
                "PAYMENT_GATEWAY_URL", "http://localhost:9090",
                "ORDER_PAYMENT_TTL", "PT3S"));

        assertThat(binder.bind("server.port", Integer.class).get()).isEqualTo(18080);
        assertThat(binder.bind("payment.gateway.url", String.class).get()).isEqualTo("http://localhost:9090");
        assertThat(binder.bind("order.payment-ttl", Duration.class).get()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("C4 환경 변수 SPRING_DATASOURCE_URL · _USERNAME · _PASSWORD가 DB 접속 설정을 덮어쓴다 (relaxed binding)")
    void c4_envVariables_overrideDatasource() throws Exception {
        Binder binder = binderWithEnv(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.example:5432/other",
                "SPRING_DATASOURCE_USERNAME", "env-user",
                "SPRING_DATASOURCE_PASSWORD", "env-secret"));

        assertThat(binder.bind("spring.datasource.url", String.class).get()).isEqualTo("jdbc:postgresql://db.example:5432/other");
        assertThat(binder.bind("spring.datasource.username", String.class).get()).isEqualTo("env-user");
        assertThat(binder.bind("spring.datasource.password", String.class).get()).isEqualTo("env-secret");
    }

    @Test
    @DisplayName("C4 application.yml에 DB 비밀번호가 하드코딩되어 있지 않다")
    void c4_noHardcodedDatabasePassword() throws Exception {
        Binder binder = binderWithEnv(Map.of());

        assertThat(binder.bind("spring.datasource.password", String.class).isBound()).isFalse();
    }
}
