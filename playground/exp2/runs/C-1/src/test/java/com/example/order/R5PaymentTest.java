package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R5 결제")
class R5PaymentTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    private long newOrder(long productId, int qty) {
        return orderOk("u1", null, productId, qty).get("id").asLong();
    }

    /** 주문·상품·쿠폰이 결제 시도 전과 같은지 확인한다. */
    private void assertUntouched(long orderId, long productId, int stock, int reserved) {
        JsonNode o = getOrder(orderId);
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("paidAt").isNull()).isTrue();
        JsonNode p = getProduct(productId);
        assertThat(p.get("stock").asInt()).isEqualTo(stock);
        assertThat(p.get("reserved").asInt()).isEqualTo(reserved);
    }

    // ---------------- R5.1 / R5.4 ----------------

    @Test
    @DisplayName("R5.1/R5.4 승인 -> 200 PAID, 본문은 R3.5 형태, paidAt 기록")
    void r5_4_approved() {
        long p = product(15000, 10);
        long id = newOrder(p, 2);
        Instant before = Instant.now().minusSeconds(1);

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok_visa");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode b = res.getBody();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(b.get("id").asLong()).isEqualTo(id);
        assertThat(b.get("status").asText()).isEqualTo("PAID");
        Instant paidAt = OffsetDateTime.parse(b.get("paidAt").asText()).toInstant();
        assertThat(paidAt).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(getOrder(id).get("status").asText()).isEqualTo("PAID");
        assertThat(getOrder(id).get("paidAt").asText()).isEqualTo(b.get("paidAt").asText());
    }

    @Test
    @DisplayName("R5.4 승인 시 각 상품의 stock 과 reserved 가 주문 수량만큼 줄어든다 (다중 상품)")
    void r5_4_stockAndReservedDecrease() {
        long p1 = product(1000, 10);
        long p2 = product(2000, 20);
        long other = product(500, 5);
        orderOk("u2", null, p1, 1);
        long id = orderOk("u1", null, p1, 3, p2, 7).get("id").asLong();

        payOk(id);

        JsonNode a = getProduct(p1);
        assertThat(a.get("stock").asInt()).isEqualTo(7);
        assertThat(a.get("reserved").asInt()).isEqualTo(1);
        JsonNode b = getProduct(p2);
        assertThat(b.get("stock").asInt()).isEqualTo(13);
        assertThat(b.get("reserved").asInt()).isZero();
        assertThat(getProduct(other).get("stock").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R5.1 cardToken 이 비었거나 공백이거나 누락이면 400, PG 미호출")
    void r5_1_invalidCardToken() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        String path = "/api/orders/" + id + "/pay";

        assertProblem(post(path, Map.of("cardToken", ""), "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, Map.of("cardToken", "   "), "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, Map.of(), "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
        assertProblem(post(path, "{broken", "Idempotency-Key", key()), 400, "VALIDATION_ERROR");
        assertThat(PG.payCallCount()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
    }

    // ---------------- R5.2 ----------------

    @Test
    @DisplayName("R5.2 없는 주문 결제 -> 404 ORDER_NOT_FOUND, PG 미호출")
    void r5_2_notFound() {
        assertProblem(payOrder(9999, key(), "tok"), 404, "ORDER_NOT_FOUND");
        assertThat(PG.payCallCount()).isZero();
    }

    @Test
    @DisplayName("R5.2 이미 PAID 인 주문을 다른 키로 다시 결제 -> 409 INVALID_STATE, PG 추가 호출 없음")
    void r5_2_alreadyPaid() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        payOk(id);

        assertProblem(payOrder(id, key(), "tok"), 409, "INVALID_STATE");

        assertThat(PG.payCallCount()).isEqualTo(1);
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R5.2 CANCELLED 주문 결제 -> 409 INVALID_STATE")
    void r5_2_cancelled() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        cancel(id);

        assertProblem(payOrder(id, key(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isZero();
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("R5.2 PAYMENT_FAILED 주문 재결제 -> 409 INVALID_STATE")
    void r5_2_paymentFailed() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        PG.decline();
        payOrder(id, key(), "tok");

        PG.approve();
        assertProblem(payOrder(id, key(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    @DisplayName("R5.2 SHIPPED / DELIVERED / REFUNDED 주문 결제 -> 409 INVALID_STATE")
    void r5_2_otherStates() {
        long p = product(1000, 10);
        long shipped = newOrder(p, 1);
        payOk(shipped);
        ship(shipped);
        long delivered = newOrder(p, 1);
        payOk(delivered);
        ship(delivered);
        deliver(delivered);
        long refunded = newOrder(p, 1);
        payOk(refunded);
        cancel(refunded);
        int payCalls = PG.payCallCount();

        assertProblem(payOrder(shipped, key(), "tok"), 409, "INVALID_STATE");
        assertProblem(payOrder(delivered, key(), "tok"), 409, "INVALID_STATE");
        assertProblem(payOrder(refunded, key(), "tok"), 409, "INVALID_STATE");
        assertThat(PG.payCallCount()).isEqualTo(payCalls);
    }

    // ---------------- R5.3 ----------------

    @Test
    @DisplayName("R5.3 PG 에 cardToken·Idempotency-Key·orderId·amount 를 그대로 전달한다")
    void r5_3_requestPassthrough() throws Exception {
        long p = product(4000, 10);
        long id = newOrder(p, 3);

        payOrder(id, "client-key-XYZ-123", "tok_weird/+=chars:42");

        assertThat(PG.payCallCount()).isEqualTo(1);
        var req = PG.lastPayRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/v1/payments");
        assertThat(req.idempotencyKey()).isEqualTo("client-key-XYZ-123");
        JsonNode body = JSON.readTree(req.body());
        assertThat(body.get("cardToken").asText()).isEqualTo("tok_weird/+=chars:42");
        assertThat(body.get("orderId").asLong()).isEqualTo(id);
        assertThat(body.get("amount").asLong()).isEqualTo(12000);
    }

    @Test
    @DisplayName("R5.3 서로 다른 결제 요청은 각자의 Idempotency-Key 를 PG 에 전달한다")
    void r5_3_distinctKeysForwarded() {
        long p = product(1000, 10);
        long id1 = newOrder(p, 1);
        long id2 = orderOk("u2", null, p, 1).get("id").asLong();

        payOrder(id1, "key-one", "tok");
        payOrder(id2, "key-two", "tok");

        assertThat(PG.requests()).extracting(r -> r.idempotencyKey()).containsExactly("key-one", "key-two");
        assertThat(PG.distinctPaymentCount()).isEqualTo(2);
    }

    // ---------------- R5.5 ----------------

    @Test
    @DisplayName("R5.5 거절 -> 402 PAYMENT_DECLINED, PAYMENT_FAILED, 예약 복원, stock 불변")
    void r5_5_declined() {
        long p = product(1000, 10);
        long id = newOrder(p, 4);
        PG.decline();

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok_bad");

        assertProblem(res, 402, "PAYMENT_DECLINED");
        JsonNode o = getOrder(id);
        assertThat(o.get("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(o.get("paidAt").isNull()).isTrue();
        JsonNode prod = getProduct(p);
        assertThat(prod.get("reserved").asInt()).isZero();
        assertThat(prod.get("stock").asInt()).isEqualTo(10);
        assertThat(prod.get("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R5.5 거절 시 쿠폰 사용도 복원된다")
    void r5_5_declinedRestoresCoupon() {
        long p = product(1000, 10);
        createCoupon("DECLCP01", "FIXED", 100);
        long id = orderOk("u1", "DECLCP01", p, 1).get("id").asLong();
        assertThat(getCoupon("DECLCP01").get("usedCount").asInt()).isEqualTo(1);
        PG.decline();

        payOrder(id, key(), "tok_bad");

        assertThat(getCoupon("DECLCP01").get("usedCount").asInt()).isZero();
    }

    // ---------------- R5.6 ----------------

    @Test
    @DisplayName("R5.6 PG 5xx -> 503 PAYMENT_GATEWAY_UNAVAILABLE, 주문·재고·쿠폰 불변")
    void r5_6_pg5xx() {
        long p = product(1000, 10);
        createCoupon("PG5XXCP1", "FIXED", 100);
        long id = orderOk("u1", "PG5XXCP1", p, 2).get("id").asLong();
        PG.fail5xx();

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok");

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.payCallCount()).isEqualTo(1);
        assertUntouched(id, p, 10, 2);
        assertThat(getCoupon("PG5XXCP1").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 연결 불가 -> 503, 주문·재고·쿠폰 불변")
    void r5_6_pgUnreachable() {
        long p = product(1000, 10);
        createCoupon("PGDOWNC1", "FIXED", 100);
        long id = orderOk("u1", "PGDOWNC1", p, 2).get("id").asLong();
        PG.unreachable();

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok");

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertUntouched(id, p, 10, 2);
        assertThat(getCoupon("PGDOWNC1").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.6 PG 가 2초 안에 응답하지 않으면 약 2초 뒤 503, 주문·재고·쿠폰 불변")
    void r5_6_pgTimeout() throws Exception {
        long p = product(1000, 10);
        createCoupon("PGSLOWC1", "FIXED", 100);
        long id = orderOk("u1", "PGSLOWC1", p, 2).get("id").asLong();
        PG.delay(3500);

        long start = System.nanoTime();
        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).as("PG 지연 3.5초를 기다리지 않고 2초 근처에서 끊어야 한다")
                .isLessThan(Duration.ofMillis(3300));
        assertThat(elapsed).as("2초 전에 포기하면 안 된다").isGreaterThanOrEqualTo(Duration.ofMillis(1800));
        assertUntouched(id, p, 10, 2);
        assertThat(getCoupon("PGSLOWC1").get("usedCount").asInt()).isEqualTo(1);
        // 지연 중이던 가짜 PG 핸들러가 끝나기를 기다려 다음 테스트를 오염시키지 않는다
        Thread.sleep(1700);
    }

    @Test
    @DisplayName("R5.6 PG 가 1초 지연되어도(2초 이내) 정상 승인된다")
    void r5_6_slowButWithinLimit() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        PG.delay(1000);

        ResponseEntity<JsonNode> res = payOrder(id, key(), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R5.6 503 이후 PG 가 복구되면 새 키로도 결제된다 (주문은 PENDING_PAYMENT 유지)")
    void r5_6_recoverAfter503() {
        long p = product(1000, 10);
        long id = newOrder(p, 1);
        PG.fail5xx();
        assertProblem(payOrder(id, key(), "tok"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        PG.approve();
        JsonNode paid = payOk(id);

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(getProduct(p).get("stock").asInt()).isEqualTo(9);
    }

    // ---------------- R5.7 ----------------

    @Test
    @DisplayName("R5.7 totalPrice 0 이면 PG 호출 없이 200 PAID (PG 가 죽어 있어도)")
    void r5_7_zeroTotalNoPgCall() {
        long p = product(2500, 10);
        createCoupon("ZEROTOT1", "FIXED", 100_000);
        JsonNode order = orderOk("u1", "ZEROTOT1", p, 2);
        assertThat(order.get("totalPrice").asLong()).isZero();
        PG.unreachable();

        ResponseEntity<JsonNode> res = payOrder(order.get("id").asLong(), key(), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PAID");
        assertThat(res.getBody().get("paidAt").isNull()).isFalse();
        assertThat(PG.payCallCount()).isZero();
        JsonNode prod = getProduct(p);
        assertThat(prod.get("stock").asInt()).isEqualTo(8);
        assertThat(prod.get("reserved").asInt()).isZero();
    }
}
