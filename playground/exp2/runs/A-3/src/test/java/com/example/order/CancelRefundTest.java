package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R7. 취소·환불")
class CancelRefundTest extends IntegrationTest {

    private Resp cancel(long orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    @Test
    @DisplayName("R7.1·R7.2 PENDING_PAYMENT → CANCELLED (200, R3.5 형태), 예약·쿠폰 사용 복원")
    void cancelPending() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        String user = uniqueUser();
        long orderId = placeOrder(user, coupon, item(productId, 4)).path("id").asLong();

        Resp r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("id").asLong()).isEqualTo(orderId);
        assertThat(r.json().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(r.json().path("items")).hasSize(1);
        assertThat(order(orderId)).isEqualTo(r.json());
        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
        assertThat(createOrder(user, coupon, List.of(item(productId, 1))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R7.3 PAID → PG 환불 성공 시 REFUNDED, stock 복원, 쿠폰 사용 복원")
    void refundPaid() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 4)).path("id").asLong();
        assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(6);
        int refundsBefore = pg.refundCalls().size();

        Resp r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("status").asText()).isEqualTo("REFUNDED");
        assertThat(pg.refundCalls()).hasSize(refundsBefore + 1);
        assertThat(pg.refundCalls().getLast()).startsWith("pay_");
        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
        assertThat(product(productId).path("reserved").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tok_refund_500", "tok_refund_slow"})
    @DisplayName("R7.3 PG 환불이 5xx·2초 초과면 503, 주문·재고·쿠폰 그대로")
    void refundGatewayFailure(String token) {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 100);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 4)).path("id").asLong();
        assertThat(pay(orderId, token).status()).isEqualTo(200);
        JsonNode before = order(orderId);
        Instant start = Instant.now();

        Resp r = cancel(orderId);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(2_900));
        assertThat(order(orderId)).isEqualTo(before);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(6);
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 0원 결제 주문은 PG 환불 없이 REFUNDED")
    void refundZeroTotal() {
        long productId = createProduct(1_000, 10);
        String coupon = createCoupon("FIXED", 10_000);
        long orderId = placeOrder(uniqueUser(), coupon, item(productId, 2)).path("id").asLong();
        assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);
        int refundsBefore = pg.refundCalls().size();

        Resp r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("status").asText()).isEqualTo("REFUNDED");
        assertThat(pg.refundCalls()).hasSize(refundsBefore);
        assertThat(product(productId).path("stock").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태(CANCELLED·REFUNDED·PAYMENT_FAILED·SHIPPED·DELIVERED)는 409 INVALID_STATE")
    void otherStates() {
        long productId = createProduct(1_000, 100);
        long cancelled = placeOrder(item(productId, 1)).path("id").asLong();
        cancel(cancelled);
        long refunded = paidOrder("tok_ok", item(productId, 1)).path("id").asLong();
        cancel(refunded);
        long failed = placeOrder(item(productId, 1)).path("id").asLong();
        pay(failed, "tok_decline");
        long shipped = paidOrder("tok_ok", item(productId, 1)).path("id").asLong();
        post("/api/orders/" + shipped + "/ship", null);
        long delivered = paidOrder("tok_ok", item(productId, 1)).path("id").asLong();
        post("/api/orders/" + delivered + "/ship", null);
        post("/api/orders/" + delivered + "/deliver", null);

        for (long id : List.of(cancelled, refunded, failed, shipped, delivered)) {
            JsonNode before = order(id);
            assertProblem(cancel(id), 409, "INVALID_STATE");
            assertThat(order(id)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("R7.1 없는 주문은 404 ORDER_NOT_FOUND")
    void notFound() {
        assertProblem(cancel(987654321L), 404, "ORDER_NOT_FOUND");
    }
}
