package com.example.order;

import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R7 취소·환불")
class CancelRefundApiTest extends IntegrationTest {

    @Test
    @DisplayName("R7.1·R7.2 PENDING_PAYMENT 취소 → 200 CANCELLED, 예약·쿠폰 복원")
    void cancelPending() {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 100);
        long orderId = api.createOrder(newUserId(), code, List.of(item(productId, 4))).assertStatus(201).id();

        JsonNode body = api.cancel(orderId).assertStatus(200).body();

        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(api.order(orderId)).isEqualTo(body);
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(10);
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
        assertThat(PG.refundCalls()).isEmpty();
    }

    @Test
    @DisplayName("R7.3 PAID 취소 → PG 환불, REFUNDED, stock 복원, 쿠폰 복원")
    void refundPaid() {
        long productId = api.createProduct(1_000, 10);
        String code = api.createCoupon("FIXED", 100);
        long orderId = api.createOrder(newUserId(), code, List.of(item(productId, 4))).assertStatus(201).id();
        api.pay(orderId).assertStatus(200);
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(6);

        JsonNode body = api.cancel(orderId).assertStatus(200).body();

        assertThat(body.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(api.product(productId).get("stock").asInt()).isEqualTo(10);
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(PG.refundCalls().getFirst()).startsWith("pay_");
    }

    @Test
    @DisplayName("R7.3 환불 PG 5xx·2초 초과 → 503, 주문·재고·쿠폰 변화 없음")
    void refundGatewayFailure() {
        for (Mode mode : List.of(Mode.SERVER_ERROR, Mode.SLOW)) {
            long productId = api.createProduct(1_000, 10);
            String code = api.createCoupon("FIXED", 100);
            long orderId = api.createOrder(newUserId(), code, List.of(item(productId, 2))).assertStatus(201).id();
            JsonNode paid = api.pay(orderId).assertStatus(200).body();
            PG.refundMode(mode);

            api.cancel(orderId).assertProblem(503, "PAYMENT_GATEWAY_UNAVAILABLE");

            assertThat(api.order(orderId)).isEqualTo(paid);
            assertThat(api.product(productId).get("stock").asInt()).isEqualTo(8);
            assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
            PG.reset();
        }
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태 → 409 INVALID_STATE, 없으면 404")
    void otherStates_return409() {
        long productId = api.createProduct(1_000, 10);
        long cancelled = api.createOrderId(newUserId(), productId, 1);
        api.cancel(cancelled).assertStatus(200);
        long refunded = api.createOrderId(newUserId(), productId, 1);
        api.pay(refunded).assertStatus(200);
        api.cancel(refunded).assertStatus(200);
        long shipped = api.createOrderId(newUserId(), productId, 1);
        api.pay(shipped).assertStatus(200);
        api.ship(shipped).assertStatus(200);
        long failed = api.createOrderId(newUserId(), productId, 1);
        PG.paymentMode(Mode.DECLINE);
        api.pay(failed).assertProblem(402, "PAYMENT_DECLINED");

        api.cancel(cancelled).assertProblem(409, "INVALID_STATE");
        api.cancel(refunded).assertProblem(409, "INVALID_STATE");
        api.cancel(shipped).assertProblem(409, "INVALID_STATE");
        api.cancel(failed).assertProblem(409, "INVALID_STATE");
        api.cancel(999_999_999L).assertProblem(404, "ORDER_NOT_FOUND");
    }
}
