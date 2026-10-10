package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.config.OrderPaymentProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.DisplayName;

/** No database needed: only exercises @ConfigurationProperties binding/validation. */
class ConfigurationPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(OrderPaymentProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

    @Test
    void defaults_areApplied() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            OrderPaymentProperties p = ctx.getBean(OrderPaymentProperties.class);
            assertThat(p.ttl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.gateway().url()).isEqualTo("http://localhost:8081");
            assertThat(p.gateway().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.gateway().readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.expiry().sweepEnabled()).isTrue();
        });
    }

    @Test
    void validIsoTtl_isBound() {
        runner.withPropertyValues("order-payment.ttl=PT30M")
                .run(ctx -> assertThat(ctx.getBean(OrderPaymentProperties.class).ttl()).isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    void malformedTtl_failsStartup() {
        runner.withPropertyValues("order-payment.ttl=15 minutes").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void zeroOrNegativeTtl_failsStartup() {
        runner.withPropertyValues("order-payment.ttl=PT0S").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.ttl=-PT5M").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void invalidGatewayUrlOrShortLease_failsStartup() {
        runner.withPropertyValues("order-payment.gateway.url=not a url").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.lease-duration=PT5S").run(ctx -> assertThat(ctx).hasFailed());
    }

    // ---------------------------------------------------------------- R17 additions: the real application.yml, env-style names

    /** Loads the real src/main/resources/application.yml so the ${ENV:default} placeholders are exercised. */
    private final ApplicationContextRunner yamlRunner = new ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsConfig.class);

    @Test
    @DisplayName("R17 application.yml defaults: ORDER_PAYMENT_TTL=PT15M, SERVER_PORT=8080, PG url default, scheduler on")
    void applicationYml_defaults() {
        yamlRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OrderPaymentProperties.class).ttl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(ctx.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(ctx.getBean(OrderPaymentProperties.class).gateway().url()).isEqualTo("http://localhost:8081");
            assertThat(ctx.getBean(OrderPaymentProperties.class).expiry().sweepEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("R17 the environment variables ORDER_PAYMENT_TTL, PAYMENT_GATEWAY_URL and SERVER_PORT are picked up by application.yml")
    void applicationYml_bindsEnvironmentVariables() {
        yamlRunner.withPropertyValues("ORDER_PAYMENT_TTL=PT45M", "PAYMENT_GATEWAY_URL=https://pg.example.com:9443/base", "SERVER_PORT=9191")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OrderPaymentProperties p = ctx.getBean(OrderPaymentProperties.class);
                    assertThat(p.ttl()).isEqualTo(Duration.ofMinutes(45));
                    assertThat(p.gateway().url()).isEqualTo("https://pg.example.com:9443/base");
                    assertThat(ctx.getEnvironment().getProperty("server.port")).isEqualTo("9191");
                });
    }

    @Test
    @DisplayName("R17 a malformed, zero or negative ORDER_PAYMENT_TTL env value makes the context fail to start and names the property")
    void applicationYml_invalidTtl_failsStartup() {
        for (String bad : new String[] {"abc", "15 minutes", "PT", "PT0S", "-PT1M", "P-1D"}) {
            yamlRunner.withPropertyValues("ORDER_PAYMENT_TTL=" + bad).run(ctx -> {
                assertThat(ctx).as("ORDER_PAYMENT_TTL=%s", bad).hasFailed();
                Throwable root = ctx.getStartupFailure();
                StringBuilder messages = new StringBuilder();
                for (Throwable t = root; t != null; t = t.getCause()) {
                    messages.append(t.getMessage()).append('\n');
                }
                assertThat(messages.toString()).contains("order-payment.ttl");
            });
        }
    }

    @Test
    @DisplayName("R17 an invalid PAYMENT_GATEWAY_URL (not absolute http/https, blank) fails startup")
    void applicationYml_invalidGatewayUrl_failsStartup() {
        for (String bad : new String[] {"ftp://pg.example.com", "pg.example.com:8080", "/relative/path", "http://"}) {
            yamlRunner.withPropertyValues("PAYMENT_GATEWAY_URL=" + bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }

    @Test
    @DisplayName("R17 datasource settings are not hard-coded in application.yml, so SPRING_DATASOURCE_URL/USERNAME/PASSWORD env vars take effect")
    void datasourceSettings_comeFromEnvironmentVariables() {
        yamlRunner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("test-env", java.util.Map.of(
                        "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.example:5432/orders",
                        "SPRING_DATASOURCE_USERNAME", "order_user",
                        "SPRING_DATASOURCE_PASSWORD", "s3cret")))).run(ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db.example:5432/orders");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("order_user");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
            for (var source : env.getPropertySources()) {
                if (source instanceof org.springframework.boot.env.OriginTrackedMapPropertySource yaml) {
                    assertThat(yaml.getPropertyNames()).noneMatch(n -> n.startsWith("spring.datasource"));
                }
            }
        });
    }

    @Test
    @DisplayName("R17 JPA is configured for validate (never update/create) and Flyway is enabled in application.yml")
    void applicationYml_schemaManagementSettings() {
        yamlRunner.run(ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isIn("validate", "none");
            assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("R17 gateway connect/read timeouts, sweep interval and batch size are validated (positive durations, batch 1..1000)")
    void otherProperties_areValidated() {
        runner.withPropertyValues("order-payment.gateway.connect-timeout=PT0S").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.gateway.read-timeout=-PT1S").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.expiry.sweep-interval=PT0S").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.expiry.batch-size=0").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.expiry.batch-size=1001").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.expiry.batch-size=1").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("order-payment.expiry.batch-size=1000").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("R17 lease-duration must be at least 2 x (connect + read timeout): exactly the minimum passes, one second less fails")
    void leaseDuration_minimumBoundary() {
        runner.withPropertyValues("order-payment.lease-duration=PT14S").run(ctx -> assertThat(ctx).hasNotFailed()); // 2 x (2s + 5s)
        runner.withPropertyValues("order-payment.lease-duration=PT13S").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("order-payment.gateway.read-timeout=PT10S", "order-payment.lease-duration=PT30S")
                .run(ctx -> assertThat(ctx).hasNotFailed()); // needs 24s
        runner.withPropertyValues("order-payment.gateway.read-timeout=PT20S", "order-payment.lease-duration=PT30S")
                .run(ctx -> assertThat(ctx).hasFailed()); // needs 44s
    }

    @Test
    @DisplayName("R17 ORDER_PAYMENT_TTL must be ISO-8601: bare numbers (read as milliseconds) and 15m/1h shorthand are rejected at startup")
    void applicationYml_nonIsoTtl_isRejected() {
        for (String nonIso : new String[] {"900", "15", "15m", "1h"}) {
            yamlRunner.withPropertyValues("ORDER_PAYMENT_TTL=" + nonIso).run(ctx -> assertThat(ctx)
                    .as("ORDER_PAYMENT_TTL=%s is not an ISO-8601 duration (Spring's lenient converter reads it as %s)", nonIso,
                            ctx.getStartupFailure() == null ? ctx.getBean(OrderPaymentProperties.class).ttl() : "n/a")
                    .hasFailed());
        }
    }
}
