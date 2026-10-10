package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderFlowSmokeTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R1.4 R3.1 R3.5 R5.3 R5.4 R8.1 생성-결제-배송-배송완료 정상 흐름")
    void happyPath_create_pay_ship_deliver() {
        long productId = newProduct(10_000, 10);

        ApiResponse created = placeOrderOk(productId, 3);
        long orderId = created.id();
        assertThat(created.header("Location")).endsWith("/api/orders/" + orderId);
        assertThat(created.text("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(created.longValue("subtotal")).isEqualTo(30_000);
        assertThat(created.longValue("totalPrice")).isEqualTo(30_000);
        assertThat(created.json().get("paidAt").isNull()).isTrue();
        assertThat(created.json().get("couponCode").isNull()).isTrue();
        assertThat(created.json().get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000);
        assertThat(OffsetDateTime.parse(created.text("expiresAt")).toInstant())
                .isAfter(OffsetDateTime.parse(created.text("createdAt")).toInstant());

        ApiResponse reserved = getProduct(productId);
        assertThat(reserved.json().get("reserved").asInt()).isEqualTo(3);
        assertThat(reserved.json().get("available").asInt()).isEqualTo(7);

        String payKey = uniqueKey();
        ApiResponse paid = pay(orderId, payKey, "tok_visa");
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.text("status")).isEqualTo("PAID");
        assertThat(paid.text("paidAt")).isNotNull();
        assertThat(PG.paymentCalls()).hasSize(1);
        assertThat(PG.paymentCalls().get(0).idempotencyKey()).isEqualTo(payKey);
        assertThat(PG.paymentCalls().get(0).amount()).isEqualTo(30_000);
        assertThat(PG.paymentCalls().get(0).cardToken()).isEqualTo("tok_visa");

        ApiResponse sold = getProduct(productId);
        assertThat(sold.json().get("stock").asInt()).isEqualTo(7);
        assertThat(sold.json().get("reserved").asInt()).isZero();

        assertThat(ship(orderId).text("status")).isEqualTo("SHIPPED");
        assertThat(deliver(orderId).text("status")).isEqualTo("DELIVERED");
        assertThat(getOrder(orderId).text("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R8.2 결제 전 ship은 409 INVALID_STATE")
    void ship_beforePay_returns409InvalidState() {
        long orderId = placeOrderOk(newProduct(1000, 5), 1).id();

        ApiResponse r = ship(orderId);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_STATE");
    }

    @Test
    @DisplayName("R2.4 R2.6 R5.5 쿠폰 할인 적용과 거절 시 복원(402)")
    void create_withCoupon_appliesDiscount_andDeclineRestores() {
        long productId = newProduct(10_000, 10);
        String coupon = newCoupon("RATE", 10, 0, 500L, 3);

        ApiResponse created = placeOrder(coupon, line(productId, 2));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.longValue("subtotal")).isEqualTo(20_000);
        assertThat(created.longValue("discount")).isEqualTo(500); // 10% = 2000, 상한 500
        assertThat(created.longValue("totalPrice")).isEqualTo(19_500);
        assertThat(getCoupon(coupon).longValue("usedCount")).isEqualTo(1);

        PG.decline();
        ApiResponse declined = pay(created.id());
        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.contentType()).startsWith("application/problem+json");
        assertThat(declined.code()).isEqualTo("PAYMENT_DECLINED");

        assertThat(getOrder(created.id()).text("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(getProduct(productId).json().get("reserved").asInt()).isZero();
        assertThat(getCoupon(coupon).longValue("usedCount")).isZero();
    }

    @Test
    @DisplayName("R5.6 R4.4 PG 5xx는 503, 상태 불변")
    void pay_gatewayError_returns503_andLeavesStateUntouched() {
        long productId = newProduct(1000, 5);
        long orderId = placeOrderOk(productId, 2).id();

        PG.http500();
        ApiResponse r = pay(orderId);

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(getOrder(orderId).text("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(getProduct(productId).json().get("reserved").asInt()).isEqualTo(2);

        // 장애로 끝난 결제는 같은 키로 다시 시도할 수 있다 (R4.4)
        PG.approve();
        assertThat(pay(orderId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R5.6 PG 2초 초과는 503")
    void pay_gatewaySlowerThan2s_returns503() {
        long orderId = placeOrderOk(newProduct(1000, 5), 1).id();

        PG.delayPayment(3000);
        long start = System.nanoTime();
        ApiResponse r = pay(orderId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.status()).isEqualTo(503);
        assertThat(elapsedMs).isBetween(1_800L, 2_900L);
        assertThat(getOrder(orderId).text("status")).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R5.7 totalPrice 0이면 PG 호출 없음")
    void pay_zeroTotal_skipsGateway() {
        long productId = newProduct(1000, 5);
        String coupon = newCoupon("FIXED", 5000, 0, null, 5); // subtotal 보다 큰 정액 -> 상한 subtotal
        ApiResponse created = placeOrder(coupon, line(productId, 1));
        assertThat(created.longValue("totalPrice")).isZero();

        ApiResponse paid = pay(created.id());

        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.text("status")).isEqualTo("PAID");
        assertThat(PG.paymentCallCount()).isZero();
    }

    @Test
    @DisplayName("R7.2 R7.4 PENDING_PAYMENT 취소는 예약 해제, 재취소는 409")
    void cancel_pending_releasesReservation() {
        long productId = newProduct(1000, 5);
        long orderId = placeOrderOk(productId, 4).id();

        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("CANCELLED");
        assertThat(getProduct(productId).json().get("reserved").asInt()).isZero();
        assertThat(cancel(orderId).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("R7.3 PAID 취소는 환불 후 stock 복원")
    void cancel_paid_refundsAndRestocks() {
        long productId = newProduct(1000, 5);
        long orderId = newPaidOrder(productId, 2);
        assertThat(getProduct(productId).json().get("stock").asInt()).isEqualTo(3);

        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("REFUNDED");
        assertThat(PG.refundCalls()).hasSize(1);
        assertThat(getProduct(productId).json().get("stock").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("R7.3 환불 5xx는 503, PAID 유지")
    void cancel_paid_refundFailure_returns503_andKeepsPaid() {
        long productId = newProduct(1000, 5);
        long orderId = newPaidOrder(productId, 2);

        PG.refund500();
        ApiResponse r = cancel(orderId);

        assertThat(r.status()).isEqualTo(503);
        assertThat(getOrder(orderId).text("status")).isEqualTo("PAID");
        assertThat(getProduct(productId).json().get("stock").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("R3.3 없는 상품·쿠폰 404, 재고 부족 409")
    void create_errors_404_and_409() {
        long productId = newProduct(1000, 2);

        assertThat(placeOrder(null, line(999_999_999L, 1)).code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(placeOrder("NOSUCH99", line(productId, 1)).code()).isEqualTo("COUPON_NOT_FOUND");
        ApiResponse tooMany = placeOrder(null, line(productId, 3));
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(getProduct(productId).json().get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.2 R11.3 주문 생성 검증 400")
    void create_validation_400() {
        long productId = newProduct(1000, 5);
        String user = uniqueUser();

        assertThat(postOrder(null, uniqueKey(), orderJson(null, line(productId, 1))).status()).isEqualTo(400);
        assertThat(postOrder(user, null, orderJson(null, line(productId, 1))).status()).isEqualTo(400);
        assertThat(postOrder(user, uniqueKey(), orderJson(null, line(productId, 1), line(productId, 2))).status()).isEqualTo(400);
        assertThat(postOrder(user, uniqueKey(), orderJson(null, line(productId, 0))).status()).isEqualTo(400);
        assertThat(postOrder(user, uniqueKey(), "{\"items\":[]}").status()).isEqualTo(400);
        ApiResponse badId = get("/api/orders/abc");
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(badId.json().get("errors").get(0).get("field").asText()).isEqualTo("id");
        assertThat(listOrders("size=abc").json().get("errors")).isNotEmpty();
        assertThat(getOrder(999_999_999L).code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R4.2 R4.3 주문 생성 재생과 불일치 422")
    void idempotency_create_replaysSameResponse_andMismatchIs422() {
        long productId = newProduct(1000, 5);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 2));

        ApiResponse first = postOrder(user, key, body);
        ApiResponse replay = postOrder(user, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.header("Location")).isEqualTo(first.header("Location"));
        assertThat(getProduct(productId).json().get("reserved").asInt()).isEqualTo(2); // 한 번만 처리

        ApiResponse mismatch = postOrder(user, key, orderJson(null, line(productId, 3)));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(postOrder(uniqueUser(), key, body).status()).isEqualTo(422); // 다른 사용자
    }

    @Test
    @DisplayName("R4.4 오류로 끝난 주문 생성 키는 재시도 가능")
    void idempotency_create_errorKeyCanBeRetried() {
        long productId = newProduct(1000, 1);
        String user = uniqueUser();
        String key = uniqueKey();
        String body = orderJson(null, line(productId, 2));

        assertThat(postOrder(user, key, body).status()).isEqualTo(409); // 재고 부족, 키는 저장되지 않음

        // 재고를 늘릴 수단이 없으므로 같은 요청이 다시 같은 오류(처리됨, IN_PROGRESS 아님)를 받는지만 본다
        ApiResponse again = postOrder(user, key, body);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R4.1 R4.2 R4.3 결제 재생·PG 1회·키 공간 독립")
    void idempotency_pay_replaysAndCallsGatewayOnce() {
        long orderId = placeOrderOk(newProduct(1000, 5), 1).id();
        String key = uniqueKey();

        ApiResponse first = pay(orderId, key, "tok_a");
        ApiResponse replay = pay(orderId, key, "tok_a");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(PG.paymentCallCount()).isEqualTo(1);
        assertThat(pay(orderId, key, "tok_other").status()).isEqualTo(422);
        // 키 공간은 엔드포인트별로 독립: 주문 생성에 쓴 키를 결제에 써도 충돌하지 않는다
        String createKey = uniqueKey();
        long productId = newProduct(1000, 5);
        ApiResponse created = postOrder(uniqueUser(), createKey, orderJson(null, line(productId, 1)));
        assertThat(pay(created.id(), createKey, "tok_b").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R4.1 R5.1 결제 Idempotency-Key 누락·cardToken 공백은 400")
    void pay_missingIdempotencyKey_returns400() {
        long orderId = placeOrderOk(newProduct(1000, 5), 1).id();

        assertThat(postPay(orderId, null, "{\"cardToken\":\"t\"}").status()).isEqualTo(400);
        assertThat(postPay(orderId, uniqueKey(), "{\"cardToken\":\"  \"}").status()).isEqualTo(400);
    }
}
