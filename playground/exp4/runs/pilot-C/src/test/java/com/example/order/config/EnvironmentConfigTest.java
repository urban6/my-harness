package com.example.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway;
import com.example.order.support.PostgresTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * C4. 런타임 설정을 환경 변수 이름 그대로 덮어쓸 수 있다.
 * 실제 프로세스 환경 변수는 테스트에서 주입할 수 없으므로, 환경 변수와 같은 대문자 이름을 담은
 * {@link SystemEnvironmentPropertySource}(이름이 "-systemEnvironment" 로 끝나 Boot 가 시스템 환경으로 취급)를
 * 가장 높은 우선순위로 환경에 넣는다. application.yml 의 기본값(localhost:5432, 8080, localhost:9090, PT15M)과
 * 모두 달라서, 값이 환경 변수 쪽에서 왔는지 구분된다.
 * 대상: SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD, SERVER_PORT, PAYMENT_GATEWAY_URL, ORDER_PAYMENT_TTL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(initializers = EnvironmentConfigTest.EnvironmentVariables.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EnvironmentConfigTest {

    private static final FakePaymentGateway ENV_PG = new FakePaymentGateway();
    private static final int PORT = freePort();

    static class EnvironmentVariables implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            Map<String, Object> env = new HashMap<>();
            env.put("SPRING_DATASOURCE_URL", PostgresTestConfig.POSTGRES.getJdbcUrl());
            env.put("SPRING_DATASOURCE_USERNAME", PostgresTestConfig.POSTGRES.getUsername());
            env.put("SPRING_DATASOURCE_PASSWORD", PostgresTestConfig.POSTGRES.getPassword());
            env.put("SERVER_PORT", String.valueOf(PORT));
            env.put("PAYMENT_GATEWAY_URL", ENV_PG.baseUrl());
            env.put("ORDER_PAYMENT_TTL", "PT7S");
            context.getEnvironment().getPropertySources()
                    .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", env));
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final TestRestTemplate rest = new TestRestTemplate(new RestTemplateBuilder()
            .rootUri("http://localhost:" + PORT));

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.set(headerPairs[i], headerPairs[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    @DisplayName("C4 SERVER_PORT / SPRING_DATASOURCE_* / PAYMENT_GATEWAY_URL / ORDER_PAYMENT_TTL 환경 변수가 모두 적용된다")
    void environmentVariablesOverrideRuntimeSettings() {
        ENV_PG.reset();
        // SERVER_PORT: 지정한 포트에서 서버가 응답하고, SPRING_DATASOURCE_*: DB 에 상품이 저장·조회된다.
        ResponseEntity<JsonNode> product = call(HttpMethod.POST, "/api/products",
                Map.of("name", "env-" + UUID.randomUUID(), "price", 1000, "stock", 5));
        assertThat(product.getStatusCode().value()).isEqualTo(201);
        long productId = product.getBody().get("id").asLong();
        assertThat(call(HttpMethod.GET, "/api/products/" + productId, null).getBody().get("stock").asInt()).isEqualTo(5);

        // ORDER_PAYMENT_TTL=PT7S -> expiresAt = createdAt + 7초
        ResponseEntity<JsonNode> order = call(HttpMethod.POST, "/api/orders",
                Map.of("items", java.util.List.of(Map.of("productId", productId, "quantity", 1))),
                "X-User-Id", "env-user", "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        Duration ttl = Duration.between(OffsetDateTime.parse(order.getBody().get("createdAt").asText()),
                OffsetDateTime.parse(order.getBody().get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofSeconds(7));

        // PAYMENT_GATEWAY_URL -> 결제 요청이 환경 변수가 가리키는 가짜 PG 로 간다.
        String payKey = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> paid = call(HttpMethod.POST,
                "/api/orders/" + order.getBody().get("id").asLong() + "/pay", Map.of("cardToken", "tok_env"),
                "Idempotency-Key", payKey);
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(ENV_PG.requestsTo("/v1/payments")).hasSize(1);
        assertThat(ENV_PG.requests().get(0).header("Idempotency-Key")).isEqualTo(payKey);
    }
}
