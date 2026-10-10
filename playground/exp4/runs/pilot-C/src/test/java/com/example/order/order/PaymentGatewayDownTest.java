package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiTestSupport;
import com.example.order.support.PostgresTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R5.6 / R7.3 의 "연결 실패" — 아무도 듣지 않는 포트를 PG 주소로 쓰는 별도 컨텍스트. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentGatewayDownTest extends ApiTestSupport {

    private static final int CLOSED_PORT = closedPort();

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void unreachableGateway(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    @DisplayName("R5.6 PG 에 연결할 수 없으면(연결 거부) 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 불변")
    void r5_6_connectionRefusedOnPayment() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();

        long startedAt = System.nanoTime();
        ResponseEntity<JsonNode> res = pay(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
        assertThat(statusOf(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertStock(productId, 10, 3);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R7.3 PG 에 연결할 수 없으면(연결 거부) 환불 취소는 503, 주문 PAID·재고·쿠폰 불변")
    void r7_3_connectionRefusedOnRefund() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 3)).get("id").asLong();
        forcePaid(orderId, "pay-offline-1"); // PG 가 죽어 있으니 DB 로 PAID 상태를 만든다
        assertStock(productId, 7, 0);

        ResponseEntity<JsonNode> res = cancel(orderId);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertStock(productId, 7, 0);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R5.7 PG 가 죽어 있어도 0원 주문은 결제된다")
    void r5_7_zeroTotalDoesNotNeedGateway() {
        long productId = newProduct(1000, 10);
        String code = newCoupon("FIXED", 1_000_000, 5);
        long orderId = newOrder(uniqueUser(), code, items(productId, 1)).get("id").asLong();

        assertStatus(pay(orderId), 200);

        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }
}
