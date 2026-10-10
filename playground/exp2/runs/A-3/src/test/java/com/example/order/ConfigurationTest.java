package com.example.order;

import com.example.order.orders.OrderPaymentProperties;
import com.example.order.payment.PaymentGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("C4. 런타임 설정은 환경 변수로 덮어쓸 수 있다")
class ConfigurationTest {

    private static Binder binder(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }

    @Test
    @DisplayName("기본값: SERVER_PORT 8080, ORDER_PAYMENT_TTL PT15M")
    void defaults() throws Exception {
        Binder binder = binder(Map.of());
        assertThat(binder.bind("server.port", Integer.class).get()).isEqualTo(8080);
        assertThat(binder.bind("order.payment", OrderPaymentProperties.class).get().ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(binder.bind("payment.gateway", PaymentGatewayProperties.class).get().timeout())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("환경 변수로 DB·포트·PG URL·결제 TTL 을 덮어쓴다")
    void overriddenByEnvironment() throws Exception {
        Binder binder = binder(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/shop",
                "SPRING_DATASOURCE_USERNAME", "shop",
                "SPRING_DATASOURCE_PASSWORD", "secret",
                "SERVER_PORT", "18080",
                "PAYMENT_GATEWAY_URL", "http://pg.internal:9090",
                "ORDER_PAYMENT_TTL", "PT3S"));
        assertThat(binder.bind("spring.datasource.url", String.class).get()).isEqualTo("jdbc:postgresql://db:5432/shop");
        assertThat(binder.bind("spring.datasource.username", String.class).get()).isEqualTo("shop");
        assertThat(binder.bind("spring.datasource.password", String.class).get()).isEqualTo("secret");
        assertThat(binder.bind("server.port", Integer.class).get()).isEqualTo(18080);
        assertThat(binder.bind("payment.gateway", PaymentGatewayProperties.class).get().url())
                .isEqualTo("http://pg.internal:9090");
        assertThat(binder.bind("order.payment", OrderPaymentProperties.class).get().ttl()).isEqualTo(Duration.ofSeconds(3));
    }
}
