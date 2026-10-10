package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R7(취소·환불), R8(배송) */
class CancelAndShipTest extends IntegrationTestBase {

    private Res cancel(long id) {
        return post("/api/orders/" + id + "/cancel", null);
    }

    private Res ship(long id) {
        return post("/api/orders/" + id + "/ship", null);
    }

    private Res deliver(long id) {
        return post("/api/orders/" + id + "/deliver", null);
    }

    // ---- R7 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("R7.2 PENDING_PAYMENT 취소 → 200 CANCELLED, 예약 복원, PG 호출 없음")
    void cancelPending() {
        long p = product(1000, 10);
        JsonNode created = order(p, 3);
        long id = created.get("id").asLong();

        Res r = cancel(id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(r.json().get("id").asLong()).isEqualTo(id);
        assertThat(orderOf(id).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    @DisplayName("R7.1 없는 주문은 404")
    void cancelMissing() {
        Res r = cancel(987654321L);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R7.3 PAID 취소 → PG 환불 후 200 REFUNDED, stock 증가, 쿠폰 복원")
    void refundPaid() {
        long p = product(1000, 10);
        String coupon = coupon("FIXED", 100, 0, null, 5);
        long id = order(items(p, 4), coupon).get("id").asLong();
        Res paid = pay(id, "tok_ok", "pay-" + uniq());
        assertThat(paid.status()).isEqualTo(200);
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(6);

        Res r = cancel(id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(couponOf(coupon).get("usedCount").asLong()).isZero();
        assertThat(GATEWAY.refunds()).hasSize(1);
        assertThat(GATEWAY.refunds().get(0).method()).isEqualTo("POST");
        assertThat(GATEWAY.refunds().get(0).path()).matches("/v1/payments/pay_\\d+/refund");
    }

    @Test
    @DisplayName("R7.3 환불 시 PG가 5xx/연결 실패/2초 초과면 503, 주문·재고·쿠폰 그대로")
    void refundGatewayFailures() {
        for (FakeGateway.Mode mode : new FakeGateway.Mode[]{
                FakeGateway.Mode.SERVER_ERROR, FakeGateway.Mode.DROP_CONNECTION, FakeGateway.Mode.HANG}) {
            GATEWAY.reset();
            long p = product(1000, 10);
            String coupon = coupon("FIXED", 100, 0, null, 5);
            long id = order(items(p, 2), coupon).get("id").asLong();
            assertThat(pay(id).status()).isEqualTo(200);
            GATEWAY.mode(mode);

            Res r = cancel(id);

            assertThat(r.status()).as(mode.name()).isEqualTo(503);
            assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
            assertThat(orderOf(id).get("status").asText()).isEqualTo("PAID");
            assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
            assertThat(couponOf(coupon).get("usedCount").asLong()).isEqualTo(1);

            GATEWAY.mode(FakeGateway.Mode.NORMAL);
            assertThat(cancel(id).status()).as("복구 후 재시도 " + mode).isEqualTo(200);
            assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("R7.4 취소·환불·실패 상태나 배송 이후에는 409 INVALID_STATE")
    void cancelInvalidStates() {
        long p = product(1000, 10);
        long cancelled = order(p, 1).get("id").asLong();
        cancel(cancelled);
        long failed = order(p, 1).get("id").asLong();
        pay(failed, FakeGateway.DECLINE_TOKEN, "pay-" + uniq());
        long refunded = paidOrder(p, 1).get("id").asLong();
        cancel(refunded);
        long shipped = paidOrder(p, 1).get("id").asLong();
        ship(shipped);
        long delivered = paidOrder(p, 1).get("id").asLong();
        ship(delivered);
        deliver(delivered);

        for (long id : new long[]{cancelled, failed, refunded, shipped, delivered}) {
            Res r = cancel(id);
            assertThat(r.status()).as("order %d", id).isEqualTo(409);
            assertThat(r.code()).isEqualTo("INVALID_STATE");
        }
        assertThat(orderOf(shipped).get("status").asText()).isEqualTo("SHIPPED");
        assertThat(orderOf(delivered).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R7.3 같은 PAID 주문의 환불(취소) 동시 요청: PG 환불은 최대 1번")
    void concurrentRefundsCallGatewayOnce() {
        long p = product(1000, 10);
        long id = paidOrder(p, 1).get("id").asLong();
        GATEWAY.delay(300);

        var results = concurrently(java.util.stream.IntStream.range(0, 8)
                .<java.util.concurrent.Callable<Res>>mapToObj(i -> () -> cancel(id)).toList());

        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(count(results, 409)).isEqualTo(7);
        assertThat(GATEWAY.refunds()).hasSize(1);
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(10);
    }

    // ---- R8 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("R8.1 PAID → SHIPPED → DELIVERED, 각각 200과 주문 본문")
    void shipAndDeliver() {
        long p = product(1000, 10);
        JsonNode paid = paidOrder(p, 2);
        long id = paid.get("id").asLong();

        Res shipped = ship(id);
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.json().get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.json().get("id").asLong()).isEqualTo(id);
        assertThat(shipped.json().get("paidAt")).isEqualTo(paid.get("paidAt"));

        Res delivered = deliver(id);
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.json().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(orderOf(id).get("status").asText()).isEqualTo("DELIVERED");
        // 배송은 재고에 영향을 주지 않는다.
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
    }

    @Test
    @DisplayName("R8.2 허용되지 않은 상태에서는 409 INVALID_STATE")
    void shipDeliverInvalidStates() {
        long p = product(1000, 10);
        long pending = order(p, 1).get("id").asLong();
        long paid = paidOrder(p, 1).get("id").asLong();
        long shipped = paidOrder(p, 1).get("id").asLong();
        ship(shipped);

        assertThat(ship(pending).status()).isEqualTo(409);
        assertThat(ship(pending).code()).isEqualTo("INVALID_STATE");
        assertThat(deliver(pending).status()).isEqualTo(409);
        assertThat(deliver(paid).status()).isEqualTo(409); // 배송 전에는 배송 완료 불가
        assertThat(deliver(paid).code()).isEqualTo("INVALID_STATE");
        assertThat(ship(shipped).status()).isEqualTo(409); // 이중 배송 불가
        deliver(shipped);
        assertThat(ship(shipped).status()).isEqualTo(409);
        assertThat(deliver(shipped).status()).isEqualTo(409);
        assertThat(orderOf(paid).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R8.2 없는 주문은 404")
    void shipDeliverMissing() {
        assertThat(ship(987654321L).status()).isEqualTo(404);
        assertThat(ship(987654321L).code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(deliver(987654321L).status()).isEqualTo(404);
    }
}
