package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.FakePaymentGateway;
import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("R5 결제")
class PaymentApiTest extends IntegrationTest {

    @Test
    @DisplayName("R5.3/R5.4 승인되면 200 PAID, paidAt 기록, stock·reserved 감소; PG 에 키·카드 토큰·금액을 그대로 전달")
    void approved() {
        long productId = createProduct(10_000, 10);
        String code = createCoupon(Map.of("value", 1_500));
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 2)));
        String key = uniqueKey();

        ApiResponse res = pay(orderId, key, "tok_4242");

        assertThat(res.status()).as(res.toString()).isEqualTo(200);
        JsonNode o = res.body();
        assertThat(o.get("status").asText()).isEqualTo("PAID");
        assertThat(OffsetDateTime.parse(o.get("paidAt").asText())).isNotNull();
        assertThat(o).isEqualTo(order(orderId));
        assertProduct(productId, 8, 0);
        assertThat(usedCount(code)).isEqualTo(1);

        assertThat(PG.paymentCalls()).hasSize(1);
        FakePaymentGateway.PaymentCall call = PG.paymentCalls().getFirst();
        assertThat(call.idempotencyKey()).isEqualTo(key);
        assertThat(call.body().get("orderId").asLong()).isEqualTo(orderId);
        assertThat(call.body().get("amount").asLong()).isEqualTo(18_500);
        assertThat(call.body().get("cardToken").asText()).isEqualTo("tok_4242");
    }

    @Test
    @DisplayName("R5.5 거절되면 402 PAYMENT_DECLINED, PAYMENT_FAILED, 예약·쿠폰 사용 복원")
    void declined() {
        long productId = createProduct(10_000, 10);
        String code = createCoupon(Map.of());
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 3)));
        PG.paymentMode(Mode.DECLINE);

        assertProblem(pay(orderId), 402, "PAYMENT_DECLINED");

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(order(orderId).get("paidAt").isNull()).isTrue();
        assertProduct(productId, 10, 0);
        assertThat(usedCount(code)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Mode.class, names = {"SERVER_ERROR", "SLOW", "DROP_CONNECTION"})
    @DisplayName("R5.6 PG 5xx·2초 초과·연결 실패면 503, 주문·재고·쿠폰은 그대로")
    void gatewayUnavailable(Mode mode) {
        long productId = createProduct(10_000, 10);
        String code = createCoupon(Map.of());
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 3)));
        PG.paymentMode(mode);

        long started = System.nanoTime();
        ApiResponse res = pay(orderId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertProblem(res, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_900)); // PG 지연(3초)을 기다리지 않는다
        JsonNode o = order(orderId);
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("paidAt").isNull()).isTrue();
        assertProduct(productId, 10, 3);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.7 totalPrice 가 0 이면 PG 를 호출하지 않고 PAID")
    void zeroTotal_skipsGateway() {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 5_000));
        long orderId = createOrder(uniqueUser(), code, List.of(item(productId, 2)));
        assertThat(order(orderId).get("totalPrice").asLong()).isZero();

        ApiResponse res = pay(orderId);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("PAID");
        assertThat(res.body().hasNonNull("paidAt")).isTrue();
        assertThat(PG.paymentCalls()).isEmpty();
        assertProduct(productId, 8, 0);
    }

    @Test
    @DisplayName("R5.2 PENDING_PAYMENT 가 아니면 409 INVALID_STATE, PG 호출 없음")
    void notPending() {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 10), 1)));
        payOk(orderId);

        assertProblem(pay(orderId), 409, "INVALID_STATE");

        long cancelled = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 10), 1)));
        action(cancelled, "cancel");
        assertProblem(pay(cancelled), 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).hasSize(1);
    }

    @Test
    @DisplayName("R5.2 없는 주문은 404")
    void notFound() {
        assertProblem(pay(999_999_999L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.1 cardToken 이 공백이거나 없으면 400; C3 400 이 404 보다 먼저")
    void invalidCardToken() {
        long orderId = createOrder(uniqueUser(), null, List.of(item(createProduct(1_000, 10), 1)));

        assertProblem(pay(orderId, uniqueKey(), "  "), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders/" + orderId + "/pay", Map.of(), "Idempotency-Key", uniqueKey()),
                400, "VALIDATION_ERROR");
        assertProblem(pay(999_999_999L, uniqueKey(), ""), 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCalls()).isEmpty();
    }
}
