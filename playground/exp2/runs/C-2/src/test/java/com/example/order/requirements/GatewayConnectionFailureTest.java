package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.FakePaymentGateway;
import com.example.order.support.IntegrationTestSupport;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R5.6 / R7.3 PG 연결 실패. PAYMENT_GATEWAY_URL이 "닫힌 포트"를 가리키는 별도 컨텍스트에서 검증한다.
 * 포트는 처음에 아무도 듣지 않는다(연결 거부). 복구/환불 시나리오에서만 테스트가 그 포트에 가짜 PG를 잠시 띄운다.
 */
class GatewayConnectionFailureTest extends IntegrationTestSupport {

    private static final int GATEWAY_PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", () -> "http://127.0.0.1:" + GATEWAY_PORT);
        registry.add("order.payment-ttl", () -> "PT15M");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("R5.6 PG에 연결할 수 없으면 503 PAYMENT_GATEWAY_UNAVAILABLE이고 주문·재고·쿠폰은 바뀌지 않는다")
    void r5_6_connectionRefused_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();

        long start = System.nanoTime();
        ApiResponse r = pay(orderId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsedMs).as("연결 거부는 타임아웃을 기다리지 않고 즉시 실패한다").isLessThan(2_500L);
        ApiResponse order = getOrder(orderId);
        assertThat(order.text("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(order.json().get("paidAt").isNull()).isTrue();
        assertThat(stock(productId)).isEqualTo(10);
        assertThat(reserved(productId)).isEqualTo(4);
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 + R4.4 연결 실패로 끝난 결제의 키는 PG가 복구된 뒤 같은 요청으로 다시 시도해 200을 받는다")
    void r5_6_connectionRefused_thenRecovered_sameKeyRetrySucceeds() {
        long productId = newProduct(1_000, 10);
        long orderId = placeOrderOk(productId, 2).id();
        String key = uniqueKey();
        assertProblem(pay(orderId, key, "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        FakePaymentGateway recovered = new FakePaymentGateway(GATEWAY_PORT);
        try {
            ApiResponse retry = pay(orderId, key, "tok");

            assertThat(retry.status()).isEqualTo(200);
            assertThat(retry.text("status")).isEqualTo("PAID");
            assertThat(recovered.paymentCalls()).hasSize(1);
            assertThat(recovered.paymentCalls().get(0).idempotencyKey()).isEqualTo(key);
            assertThat(stock(productId)).isEqualTo(8);
        } finally {
            recovered.stop();
        }
    }

    @Test
    @DisplayName("R7.3 환불 중 PG에 연결할 수 없으면 503이고 주문(PAID)·재고·쿠폰은 바뀌지 않으며, 복구 후 환불된다")
    void r7_3_refundConnectionFailure_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 3)).id();

        FakePaymentGateway gateway = new FakePaymentGateway(GATEWAY_PORT);
        try {
            assertThat(pay(orderId).status()).isEqualTo(200);
        } finally {
            gateway.stop();
        }
        assertThat(stock(productId)).isEqualTo(7);

        ApiResponse failed = cancel(orderId);

        assertProblem(failed, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(getOrder(orderId).text("status")).isEqualTo("PAID");
        assertThat(stock(productId)).isEqualTo(7);
        assertThat(usedCount(coupon)).isEqualTo(1);

        FakePaymentGateway recovered = new FakePaymentGateway(GATEWAY_PORT);
        try {
            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                    .until(() -> cancel(orderId).status() == 200);
            assertThat(getOrder(orderId).text("status")).isEqualTo("REFUNDED");
            assertThat(stock(productId)).isEqualTo(10);
            assertThat(usedCount(coupon)).isZero();
        } finally {
            recovered.stop();
        }
    }
}
