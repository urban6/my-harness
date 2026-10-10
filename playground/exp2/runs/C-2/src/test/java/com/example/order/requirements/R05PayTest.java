package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R5. 결제 (PG 연결 실패는 R05GatewayConnectionFailureTest, 만료 후 결제는 R06ExpirationTest) */
class R05PayTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ R5.1

    @Test
    @DisplayName("R5.1 결제 승인 응답은 R3.5 형태의 본문이고 이후 조회 본문과 같다")
    void r5_1_pay_responseBody_hasOrderShape_andEqualsGet() {
        long orderId = placeOrderOk(newProduct(1_000, 5), 2).id();

        ApiResponse r = pay(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(getOrder(orderId).json()).isEqualTo(r.json());
    }

    @ParameterizedTest(name = "R5.1 잘못된 결제 본문 {0} -> 400")
    @ValueSource(strings = {"{}", "{\"cardToken\":null}", "{\"cardToken\":\"\"}", "{\"cardToken\":\"   \"}",
            "{\"cardToken\":123}", "{\"cardToken\":", "", "[]"})
    @DisplayName("R5.1 cardToken이 없거나 공백이거나 본문이 잘못되면 400이고 PG를 호출하지 않는다")
    void r5_1_invalidCardToken_returns400(String body) {
        long orderId = placeOrderOk(newProduct(1_000, 5), 1).id();

        ApiResponse r = postPay(orderId, uniqueKey(), body);

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(getOrder(orderId).text("status")).isEqualTo("PENDING_PAYMENT");
    }

    // ------------------------------------------------------------------ R5.2

    @ParameterizedTest(name = "R5.2 {0} 주문은 결제할 수 없다")
    @ValueSource(strings = {"PAID", "SHIPPED", "DELIVERED", "CANCELLED", "PAYMENT_FAILED", "REFUNDED"})
    @DisplayName("R5.2 PENDING_PAYMENT가 아닌 주문은 409 INVALID_STATE이고 PG를 호출하지 않으며 상태가 변하지 않는다")
    void r5_2_nonPendingOrder_returns409(String status) {
        long orderId = newOrderInStatus(status);
        PG.reset();

        ApiResponse r = pay(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(getOrder(orderId).text("status")).isEqualTo(status);
    }

    @Test
    @DisplayName("R5.2 없는 주문은 404 ORDER_NOT_FOUND이다")
    void r5_2_unknownOrder_returns404() {
        assertProblem(pay(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R5.2 숫자가 아닌 주문 id는 400이다")
    void r5_2_nonNumericId_returns400() {
        ApiResponse r = post("/api/orders/abc/pay", headers("Idempotency-Key", uniqueKey()), "{\"cardToken\":\"t\"}");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ R5.3

    @Test
    @DisplayName("R5.3 PG에는 cardToken을 그대로, Idempotency-Key는 클라이언트가 보낸 값 그대로 전달한다")
    void r5_3_gatewayReceives_cardToken_and_idempotencyKey_asIs() {
        long orderId = placeOrderOk(newProduct(2_500, 5), 2).id();
        String key = uniqueKey();
        String cardToken = "tok_Visa-4242/ABC+def==";

        ApiResponse r = pay(orderId, key, cardToken);

        assertThat(r.status()).isEqualTo(200);
        assertThat(PG.paymentCalls()).hasSize(1);
        var call = PG.paymentCalls().get(0);
        assertThat(call.idempotencyKey()).isEqualTo(key);
        assertThat(call.cardToken()).isEqualTo(cardToken);
        assertThat(call.orderId()).isEqualTo(orderId);
        assertThat(call.amount()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("R5.3 PG 요청 amount는 할인이 반영된 totalPrice이다")
    void r5_3_gatewayAmount_isTotalPriceAfterDiscount() {
        String coupon = newCoupon("FIXED", 1_500, 0, null, 5);
        long productId = newProduct(10_000, 5);
        ApiResponse order = placeOrder(coupon, line(productId, 1));

        pay(order.id());

        assertThat(PG.paymentCalls().get(0).amount()).isEqualTo(8_500);
    }

    // ------------------------------------------------------------------ R5.4

    @Test
    @DisplayName("R5.4 C2 승인되면 200, status=PAID, paidAt(오프셋 포함 ISO-8601)이 기록되고 각 상품의 stock·reserved가 주문 수량만큼 준다")
    void r5_4_approved_marksPaid_andDecrementsStockAndReserved() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(2_000, 10);
        long orderId = placeOrder(null, line(p1, 3), line(p2, 5)).id();
        Instant before = Instant.now();

        ApiResponse r = pay(orderId);

        Instant after = Instant.now();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("PAID");
        assertThat(r.text("paidAt")).isNotNull().matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(instant(r, "paidAt")).isBetween(before.minusSeconds(5), after.plusSeconds(5));
        assertThat(stock(p1)).isEqualTo(7);
        assertThat(reserved(p1)).isZero();
        assertThat(stock(p2)).isEqualTo(5);
        assertThat(reserved(p2)).isZero();
    }

    @Test
    @DisplayName("R5.4 승인 후 조회하면 PAID이고 paidAt이 응답과 같다")
    void r5_4_approved_persistsPaidAt() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();

        ApiResponse r = pay(orderId);

        ApiResponse fetched = getOrder(orderId);
        assertThat(fetched.text("status")).isEqualTo("PAID");
        assertThat(instant(fetched, "paidAt")).isEqualTo(instant(r, "paidAt"));
    }

    @Test
    @DisplayName("R5.4 승인된 주문은 쿠폰 사용을 유지한다 (usedCount 그대로)")
    void r5_4_approved_keepsCouponUse() {
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        ApiResponse order = placeOrder(coupon, line(newProduct(1_000, 10), 1));

        pay(order.id());

        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R5.5

    @Test
    @DisplayName("R5.5 거절되면 402 PAYMENT_DECLINED, 주문은 PAYMENT_FAILED가 되고 예약·쿠폰 사용이 복원된다 (stock은 그대로)")
    void r5_5_declined_returns402_andRestores() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(p1, 3), line(p2, 2)).id();
        PG.decline();

        ApiResponse r = pay(orderId);

        assertProblem(r, 402, "PAYMENT_DECLINED");
        ApiResponse order = getOrder(orderId);
        assertThat(order.text("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(order.json().get("paidAt").isNull()).isTrue();
        assertThat(reserved(p1)).isZero();
        assertThat(reserved(p2)).isZero();
        assertThat(stock(p1)).isEqualTo(10);
        assertThat(stock(p2)).isEqualTo(10);
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R5.5 거절 후 복원된 재고는 다른 주문이 예약할 수 있다")
    void r5_5_declined_releasedStockIsOrderable() {
        long productId = newProduct(1_000, 3);
        long orderId = placeOrderOk(productId, 3).id();
        assertThat(placeOrder(null, line(productId, 1)).status()).isEqualTo(409);
        PG.decline();
        pay(orderId);

        assertThat(placeOrder(null, line(productId, 3)).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ R5.6

    private void assertUntouched(long orderId, long productId, int stockBefore, int reservedBefore, String coupon) {
        ApiResponse order = getOrder(orderId);
        assertThat(order.text("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(order.json().get("paidAt").isNull()).isTrue();
        assertThat(stock(productId)).isEqualTo(stockBefore);
        assertThat(reserved(productId)).isEqualTo(reservedBefore);
        if (coupon != null) {
            assertThat(usedCount(coupon)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("R5.6 PG가 5xx를 주면 503 PAYMENT_GATEWAY_UNAVAILABLE이고 주문·재고·쿠폰은 바뀌지 않는다")
    void r5_6_gateway5xx_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();
        PG.http500();

        ApiResponse r = pay(orderId);

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(PG.paymentCallCount()).isEqualTo(1);
        assertUntouched(orderId, productId, 10, 4, coupon);
    }

    @Test
    @DisplayName("R5.6 PG가 2초 안에 응답하지 않으면(지연 3000ms) 약 2초 뒤 503이고 주문·재고·쿠폰은 바뀌지 않는다")
    void r5_6_gatewayTimeout_returns503_after2s_andLeavesStateUntouched() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 4)).id();
        PG.delayPayment(3_000);

        long start = System.nanoTime();
        ApiResponse r = pay(orderId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertProblem(r, 503, "PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(elapsedMs).as("2초에 끊어야 하므로 3초 지연 응답을 기다리지 않는다").isBetween(1_800L, 2_900L);
        assertUntouched(orderId, productId, 10, 4, coupon);
    }

    @Test
    @DisplayName("R5.6 PG가 2초 이내(지연 1000ms)에 승인하면 정상 200이다")
    void r5_6_gatewaySlowButWithin2s_succeeds() {
        long orderId = placeOrderOk(newProduct(1_000, 10), 1).id();
        PG.delayPayment(1_000);

        ApiResponse r = pay(orderId);

        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.6 503 뒤에 PG가 복구되면 같은 주문을 다시 결제할 수 있다")
    void r5_6_afterGatewayRecovery_payCanBeRetried() {
        long productId = newProduct(1_000, 10);
        long orderId = placeOrderOk(productId, 2).id();
        PG.http500();
        assertThat(pay(orderId).status()).isEqualTo(503);

        PG.approve();
        ApiResponse r = pay(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(reserved(productId)).isZero();
    }

    // ------------------------------------------------------------------ R5.7

    @Test
    @DisplayName("R5.7 totalPrice가 0이면 PG를 호출하지 않고 바로 승인 처리한다 (PG가 장애여도 200)")
    void r5_7_zeroTotal_skipsGateway_andApproves() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("RATE", 100, 0, null, 5);
        ApiResponse order = placeOrder(coupon, line(productId, 3));
        assertThat(order.longValue("totalPrice")).isZero();
        PG.http500();

        ApiResponse r = pay(order.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("PAID");
        assertThat(r.text("paidAt")).isNotNull();
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(stock(productId)).isEqualTo(7);
        assertThat(reserved(productId)).isZero();
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0이어도 멱등 키 재생은 동일하게 동작한다 (PG 0회, stock 한 번만 감소)")
    void r5_7_zeroTotal_replayIsIdempotent() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 5_000, 0, null, 5);
        long orderId = placeOrder(coupon, line(productId, 1)).id();
        String key = uniqueKey();

        ApiResponse first = pay(orderId, key, "tok");
        ApiResponse replay = pay(orderId, key, "tok");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(PG.paymentCallCount()).isZero();
        assertThat(stock(productId)).isEqualTo(9);
    }

    @Test
    @DisplayName("R5.7 totalPrice가 0보다 크면 PG를 호출한다 (대조군)")
    void r5_7_positiveTotal_callsGateway() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 999, 0, null, 5);
        ApiResponse order = placeOrder(coupon, line(productId, 1));
        assertThat(order.longValue("totalPrice")).isEqualTo(1);

        pay(order.id());

        assertThat(PG.paymentCalls()).hasSize(1);
        assertThat(PG.paymentCalls().get(0).amount()).isEqualTo(1);
    }
}
