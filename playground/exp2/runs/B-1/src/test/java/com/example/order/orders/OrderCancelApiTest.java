package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("R7 취소·환불")
class OrderCancelApiTest extends IntegrationTest {

    @Test
    @DisplayName("R7.1/R7.2 PENDING_PAYMENT 취소 → 200 CANCELLED, 예약·쿠폰 사용 복원")
    void cancelPending() {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of());
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 3)));

        ApiResponse res = action(orderId, "cancel");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.body()).isEqualTo(order(orderId));
        assertProduct(productId, 10, 0);
        assertThat(usedCount(code)).isZero();
        assertThat(PG.refundCalls()).isEmpty();
    }

    @Test
    @DisplayName("R7.3 PAID 취소 → PG 환불 후 REFUNDED, stock 복구, 쿠폰 사용 복원")
    void cancelPaid_refunds() {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 100));
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 3)));
        payOk(orderId);
        assertProduct(productId, 7, 0);

        ApiResponse res = action(orderId, "cancel");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("REFUNDED");
        assertProduct(productId, 10, 0);
        assertThat(usedCount(code)).isZero();
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(PG.refundCalls().getFirst()).startsWith("pay_");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Mode.class, names = {"SERVER_ERROR", "SLOW", "DROP_CONNECTION"})
    @DisplayName("R7.3 PG 환불이 5xx·2초 초과·연결 실패면 503, 주문·재고·쿠폰은 그대로")
    void refundGatewayUnavailable(Mode mode) {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 100));
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 3)));
        payOk(orderId);
        PG.refundMode(mode);

        assertProblem(action(orderId, "cancel"), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertProduct(productId, 7, 0);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3 PG 결제 없이 PAID 된(0원) 주문은 PG 호출 없이 환불된다")
    void cancelZeroTotalPaid() {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 10_000));
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 1)));
        payOk(orderId);

        ApiResponse res = action(orderId, "cancel");

        assertThat(res.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(PG.refundCalls()).isEmpty();
        assertProduct(productId, 10, 0);
    }

    @Test
    @DisplayName("R7.4 그 밖의 상태(CANCELLED·SHIPPED·DELIVERED·PAYMENT_FAILED·REFUNDED)는 409 INVALID_STATE")
    void otherStates() {
        long productId = createProduct(1_000, 100);

        long cancelled = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        action(cancelled, "cancel");
        assertProblem(action(cancelled, "cancel"), 409, "INVALID_STATE");

        long shipped = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        payOk(shipped);
        action(shipped, "ship");
        assertProblem(action(shipped, "cancel"), 409, "INVALID_STATE");

        action(shipped, "deliver");
        assertProblem(action(shipped, "cancel"), 409, "INVALID_STATE");

        long refunded = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        payOk(refunded);
        action(refunded, "cancel");
        assertProblem(action(refunded, "cancel"), 409, "INVALID_STATE");

        long failed = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        PG.paymentMode(Mode.DECLINE);
        pay(failed);
        assertProblem(action(failed, "cancel"), 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("R7.1 없는 주문은 404")
    void notFound() {
        assertProblem(action(999_999_999L, "cancel"), 404, "ORDER_NOT_FOUND");
    }
}
