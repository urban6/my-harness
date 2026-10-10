package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.order.OrderApplication;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * C4: 환경 변수(SPRING_DATASOURCE_URL/USERNAME/PASSWORD, SERVER_PORT, PAYMENT_GATEWAY_URL, ORDER_PAYMENT_TTL)로 런타임 설정을 덮어쓸 수 있다.
 *
 * <p>프로퍼티 오버라이드로는 relaxed binding(SPRING_DATASOURCE_*)과 플레이스홀더(${SERVER_PORT:8080}) 경로를 진짜로 검증할 수 없으므로,
 * 테스트 클래스패스로 앱을 별도 JVM 에 띄우고 <b>진짜 프로세스 환경 변수</b>를 주입한 뒤 HTTP 로 동작을 확인한다.
 */
@DisplayName("C4. 환경 변수로 설정 덮어쓰기 (별도 프로세스)")
class C4EnvironmentVariablesTest extends IntegrationTestBase {

    private Process app;
    private File logFile;

    @AfterEach
    void stopApp() throws InterruptedException {
        if (app != null) {
            app.destroy();
            if (!app.waitFor(15, TimeUnit.SECONDS)) {
                app.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true); // Tomcat 과 같은 조건 (직전 실행의 TIME_WAIT 소켓 무시)
            s.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void startApp(Map<String, String> env, int port) throws Exception {
        String javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        logFile = Files.createTempFile("c4-app-", ".log").toFile();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-XX:TieredStopAtLevel=1", "-cp", System.getProperty("java.class.path"),
                OrderApplication.class.getName());
        pb.environment().keySet().removeIf(k -> k.startsWith("SPRING_") || k.equals("SERVER_PORT")
                || k.equals("PAYMENT_GATEWAY_URL") || k.equals("ORDER_PAYMENT_TTL"));
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);
        app = pb.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) {
                throw new IllegalStateException("앱 프로세스가 종료됨 exit=" + app.exitValue() + "\n" + tail());
            }
            try {
                ResponseEntity<String> r = rest.getForEntity(URI.create("http://localhost:" + port + "/api/products/1"), String.class);
                if (r.getStatusCode().value() > 0) {
                    return;
                }
            } catch (RuntimeException notYet) {
                sleepMillis(500);
            }
        }
        throw new IllegalStateException("앱이 시간 안에 뜨지 않음\n" + tail());
    }

    private String tail() throws IOException {
        List<String> lines = Files.readAllLines(logFile.toPath());
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private ResponseEntity<String> call(int port, HttpMethod method, String path, Object body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        String payload = body == null ? null : toJson(body);
        return rest.exchange(URI.create("http://localhost:" + port + path), method, new HttpEntity<>(payload, h), String.class);
    }

    private Map<String, String> dbEnv() {
        return Map.of(
                "SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
    }

    @Test
    @DisplayName("C4 SPRING_DATASOURCE_*, SERVER_PORT, PAYMENT_GATEWAY_URL, ORDER_PAYMENT_TTL 환경 변수가 모두 반영된다")
    void c4_allEnvironmentVariables_areHonored() throws Exception {
        int port = freePort();
        Map<String, String> env = new java.util.HashMap<>(dbEnv());
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("PAYMENT_GATEWAY_URL", PG.baseUrl());
        env.put("ORDER_PAYMENT_TTL", "PT7S");
        startApp(env, port);

        // SERVER_PORT: 지정한 포트에서 응답한다 (위 startApp 의 기동 확인). DB: 같은 DB 에 쓰므로 메인 컨텍스트에서 보인다.
        ResponseEntity<String> product = call(port, HttpMethod.POST, "/api/products", Map.of("name", "env-p", "price", 1000, "stock", 5));
        assertThat(product.getStatusCode().value()).isEqualTo(201);
        long productId = json(product).get("id").asLong();
        assertThat(json(getProduct(productId)).get("name").asText()).as("SPRING_DATASOURCE_*: 같은 DB").isEqualTo("env-p");

        ResponseEntity<String> order = call(port, HttpMethod.POST, "/api/orders",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 1))),
                "X-User-Id", uid("u"), "Idempotency-Key", uid("k"));
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        JsonNode o = json(order);
        Duration ttl = Duration.between(Instant.parse(o.get("createdAt").asText()), Instant.parse(o.get("expiresAt").asText()));
        assertThat(ttl).as("ORDER_PAYMENT_TTL=PT7S").isEqualTo(Duration.ofSeconds(7));

        ResponseEntity<String> paid = call(port, HttpMethod.POST, "/api/orders/" + o.get("id").asLong() + "/pay",
                Map.of("cardToken", "tok_env"), "Idempotency-Key", uid("pk"));
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(PG.paymentCalls()).as("PAYMENT_GATEWAY_URL: 지정한 PG 스텁으로 호출").isEqualTo(1);
    }

    @Test
    @DisplayName("C4 SERVER_PORT 를 지정하지 않으면 8080, ORDER_PAYMENT_TTL 을 지정하지 않으면 PT15M 이다")
    void c4_defaults_port8080_andTtl15Minutes() throws Exception {
        assumeTrue(isPortFree(8080), "8080 포트가 이미 사용 중이라 기본 포트 검증을 건너뜀");
        Map<String, String> env = new java.util.HashMap<>(dbEnv());
        env.put("PAYMENT_GATEWAY_URL", PG.baseUrl());
        startApp(env, 8080);

        ResponseEntity<String> product = call(8080, HttpMethod.POST, "/api/products", Map.of("name", "env-d", "price", 1000, "stock", 5));
        long productId = json(product).get("id").asLong();
        ResponseEntity<String> order = call(8080, HttpMethod.POST, "/api/orders",
                Map.of("items", List.of(Map.of("productId", productId, "quantity", 1))),
                "X-User-Id", uid("u"), "Idempotency-Key", uid("k"));

        JsonNode o = json(order);
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        assertThat(Duration.between(Instant.parse(o.get("createdAt").asText()), Instant.parse(o.get("expiresAt").asText())))
                .isEqualTo(Duration.ofMinutes(15));
    }
}
