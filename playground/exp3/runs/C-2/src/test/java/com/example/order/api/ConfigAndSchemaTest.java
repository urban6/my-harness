package com.example.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.config.OrderProperties;
import com.example.order.config.PaymentGatewayProperties;
import com.example.order.order.OrderExpiryScheduler;
import com.example.order.support.IntegrationTestBase;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("REQ-16 schema (Flyway) and configuration (environment variables)")
class ConfigAndSchemaTest extends IntegrationTestBase {

    @Autowired
    OrderProperties orderProperties;
    @Autowired
    PaymentGatewayProperties gatewayProperties;
    @Autowired
    ApplicationContext context;

    // ---------------------------------------------------------------- Flyway / schema

    @Test
    @DisplayName("REQ-16 Flyway V1 이 성공적으로 적용되었고 6개 테이블이 생성된다")
    void flywayCreatedAllTables() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(tables).contains("products", "coupons", "orders", "order_items", "payments", "idempotency_keys");
    }

    @Test
    @DisplayName("REQ-16 금액 컬럼은 모두 BIGINT (부동소수점/NUMERIC 없음)")
    void moneyColumnsAreBigint() {
        Map<String, List<String>> money = Map.of(
                "products", List.of("price"),
                "coupons", List.of("discount_value", "min_order_amount", "max_discount_amount"),
                "orders", List.of("subtotal", "discount", "total_price"),
                "order_items", List.of("unit_price"),
                "payments", List.of("amount"));
        money.forEach((table, columns) -> columns.forEach(column -> {
            String type = jdbc.queryForObject("select data_type from information_schema.columns "
                    + "where table_schema = 'public' and table_name = ? and column_name = ?", String.class, table,
                    column);
            assertThat(type).as(table + "." + column).isEqualTo("bigint");
        }));
    }

    @Test
    @DisplayName("REQ-16 시각 컬럼은 TIMESTAMPTZ")
    void timestampColumnsAreTimestamptz() {
        for (String column : List.of("created_at", "expires_at", "paid_at", "updated_at")) {
            String type = jdbc.queryForObject("select data_type from information_schema.columns "
                    + "where table_schema = 'public' and table_name = 'orders' and column_name = ?", String.class,
                    column);
            assertThat(type).as(column).isEqualTo("timestamp with time zone");
        }
    }

    @Test
    @DisplayName("REQ-16 설계된 제약/유니크/FK 가 존재한다")
    void designedConstraintsExist() {
        List<String> names = jdbc.queryForList("select conname from pg_constraint", String.class);

        assertThat(names).contains("uq_coupons_code", "uq_payments_order", "uq_idempotency_scope",
                "uq_order_items_order_product", "ck_products_reserved_le_stock", "ck_coupons_used_le_total",
                "ck_coupons_valid_period", "ck_orders_status", "ck_orders_amounts", "ck_orders_expiry",
                "ck_orders_paid_at", "fk_orders_coupon", "fk_order_items_order", "fk_order_items_product",
                "fk_payments_order", "fk_idempotency_order");
    }

    @Test
    @DisplayName("REQ-16 목록/만료용 인덱스가 있고 만료 인덱스는 PENDING_PAYMENT 부분 인덱스다")
    void indexesExist() {
        List<String> names = jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'",
                String.class);
        String pendingDef = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_orders_pending_expiry'", String.class);

        assertThat(names).contains("idx_orders_user_id_id", "idx_orders_status_id", "idx_orders_pending_expiry");
        assertThat(pendingDef).contains("WHERE").contains("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("REQ-16/05 DB 안전망: reserved > stock 인 상품 INSERT 는 CHECK 위반으로 거부")
    void dbRejectsReservedAboveStock() {
        assertThatThrownBy(() -> jdbc.update("insert into products (name, price, stock, reserved) values ('x', 1, 1, 2)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_products_reserved_le_stock");
    }

    @Test
    @DisplayName("REQ-16/06 DB 안전망: used_count > total_quantity 인 쿠폰은 CHECK 위반으로 거부")
    void dbRejectsCouponOveruse() {
        assertThatThrownBy(() -> jdbc.update("insert into coupons (code, coupon_type, discount_value, total_quantity, "
                + "used_count, valid_from, valid_until) values ('over-" + System.nanoTime()
                + "', 'FIXED', 10, 1, 2, now(), now() + interval '1 day')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_coupons_used_le_total");
    }

    @Test
    @DisplayName("REQ-16 DB 안전망: discount > subtotal 인 주문 / 알 수 없는 status 는 CHECK 위반으로 거부")
    void dbRejectsInconsistentOrders() {
        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id, status, subtotal, discount, total_price, "
                + "created_at, expires_at, updated_at) values ('u', 'PENDING_PAYMENT', 10, 20, -10, now(), "
                + "now() + interval '1 hour', now())")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into orders (user_id, status, subtotal, discount, total_price, "
                + "created_at, expires_at, updated_at) values ('u', 'BOGUS', 10, 0, 10, now(), "
                + "now() + interval '1 hour', now())")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint");
    }

    @Test
    @DisplayName("REQ-16 DB 안전망: 쿠폰 code 중복은 uq_coupons_code 로 거부")
    void dbRejectsDuplicateCouponCode() {
        String sql = "insert into coupons (code, coupon_type, discount_value, total_quantity, valid_from, valid_until) "
                + "values (?, 'FIXED', 10, 1, now(), now() + interval '1 day')";
        String code = "dup-" + System.nanoTime();
        jdbc.update(sql, code);

        assertThatThrownBy(() -> jdbc.update(sql, code)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_coupons_code");
    }

    // ---------------------------------------------------------------- configuration binding

    @Test
    @DisplayName("REQ-16 기본 설정이 바인딩된다 (ORDER_PAYMENT_TTL 기본 PT15M, 연결 타임아웃 PT2S)")
    void defaultPropertiesAreBound() {
        assertThat(orderProperties.paymentTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(gatewayProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("REQ-13 테스트 프로파일에서 만료 스케줄러 빈은 order.expiry-sweep-enabled=false 로 등록되지 않는다")
    void schedulerBeanIsDisabledWhenSweepDisabled() {
        assertThat(orderProperties.expirySweepEnabled()).isFalse();
        assertThat(context.getBeansOfType(OrderExpiryScheduler.class)).isEmpty();
    }

    private static MutablePropertySources applicationYml(Map<String, Object> env) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("env", env));
        for (PropertySource<?> ps : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            sources.addLast(ps);
        }
        return sources;
    }

    @Test
    @DisplayName("REQ-16 환경변수 미설정 시 application.yml 기본값: SERVER_PORT=8080, PAYMENT_GATEWAY_URL=http://localhost:9090, ORDER_PAYMENT_TTL=PT15M")
    void ymlDefaultsWithoutEnvironment() throws IOException {
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(applicationYml(Map.of()));

        assertThat(resolver.getProperty("server.port")).isEqualTo("8080");
        assertThat(resolver.getProperty("payment.gateway.url")).isEqualTo("http://localhost:9090");
        assertThat(resolver.getProperty("order.payment-ttl")).isEqualTo("PT15M");
    }

    @Test
    @DisplayName("REQ-16 환경변수가 application.yml 에 바인딩된다 (SPRING_DATASOURCE_*, SERVER_PORT, PAYMENT_GATEWAY_URL, ORDER_PAYMENT_TTL)")
    void ymlBindsEnvironmentVariables() throws IOException {
        Map<String, Object> env = new HashMap<>();
        env.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/orders");
        env.put("SPRING_DATASOURCE_USERNAME", "app");
        env.put("SPRING_DATASOURCE_PASSWORD", "secret");
        env.put("SERVER_PORT", "9191");
        env.put("PAYMENT_GATEWAY_URL", "http://pg.internal:8443");
        env.put("ORDER_PAYMENT_TTL", "PT45M");
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(applicationYml(env));

        assertThat(resolver.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/orders");
        assertThat(resolver.getProperty("spring.datasource.username")).isEqualTo("app");
        assertThat(resolver.getProperty("spring.datasource.password")).isEqualTo("secret");
        assertThat(resolver.getProperty("server.port")).isEqualTo("9191");
        assertThat(resolver.getProperty("payment.gateway.url")).isEqualTo("http://pg.internal:8443");
        assertThat(resolver.getProperty("order.payment-ttl")).isEqualTo("PT45M");
    }

    private static Binder binder(Map<String, Object> env) throws IOException {
        MutablePropertySources sources = applicationYml(env);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    @DisplayName("REQ-16 ORDER_PAYMENT_TTL 은 ISO-8601 Duration 으로 바인딩된다 (기본 15분, PT45M 오버라이드 45분)")
    void orderPaymentTtlBindsAsDuration() throws IOException {
        OrderProperties defaults = binder(Map.of()).bind("order", OrderProperties.class).get();
        OrderProperties overridden = binder(Map.of("ORDER_PAYMENT_TTL", "PT45M")).bind("order", OrderProperties.class)
                .get();

        assertThat(defaults.paymentTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(overridden.paymentTtl()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    @DisplayName("REQ-16 ORDER_PAYMENT_TTL 이 0 / 음수 / 형식 오류면 바인딩(=기동)이 실패한다")
    void invalidPaymentTtlFailsBinding() {
        for (String bad : List.of("PT0S", "-PT1M", "15 minutes")) {
            assertThatThrownBy(() -> binder(Map.of("ORDER_PAYMENT_TTL", bad)).bind("order", OrderProperties.class).get())
                    .as(bad).isInstanceOf(BindException.class);
        }
    }
}
