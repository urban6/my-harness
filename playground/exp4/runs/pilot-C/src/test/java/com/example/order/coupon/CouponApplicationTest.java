package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** R2.4(할인 계산) ~ R2.6(적용 조건·소진·복원). 시각 경계는 {@code ClockBoundaryTest} 가 맡는다. */
class CouponApplicationTest extends IntegrationTestBase {

    private String couponWith(String type, long value, Long min, Long max, int total) {
        Map<String, Object> body = couponBody(uniqueCode(), type, value);
        if (min != null) {
            body.put("minOrderAmount", min);
        }
        if (max != null) {
            body.put("maxDiscountAmount", max);
        }
        body.put("totalQuantity", total);
        return createCoupon(body).get("code").asText();
    }

    private JsonNode orderWith(String couponCode, long unitPrice, int quantity) {
        long productId = newProduct(unitPrice, 1000);
        return newOrder(uniqueUser(), couponCode, items(productId, quantity));
    }

    private void assertAmounts(JsonNode order, long subtotal, long discount, long totalPrice) {
        assertThat(order.get("subtotal").asLong()).as("subtotal").isEqualTo(subtotal);
        assertThat(order.get("discount").asLong()).as("discount").isEqualTo(discount);
        assertThat(order.get("totalPrice").asLong()).as("totalPrice").isEqualTo(totalPrice);
    }

    // ------------------------------------------------------------------ R2.4 할인 계산

    @Test
    @DisplayName("R2.4 쿠폰이 없으면 discount=0, totalPrice=subtotal")
    void r2_4_noCouponMeansNoDiscount() {
        JsonNode order = orderWith(null, 2500, 4);

        assertAmounts(order, 10_000, 0, 10_000);
        assertThat(order.get("couponCode").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.4 FIXED 는 value 만큼 할인한다")
    void r2_4_fixedDiscount() {
        String code = couponWith("FIXED", 3000, null, null, 10);

        JsonNode order = orderWith(code, 10_000, 2);

        assertAmounts(order, 20_000, 3000, 17_000);
        assertThat(order.get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R2.4 FIXED value 가 subtotal 보다 크면 subtotal 로 상한되어 totalPrice=0")
    void r2_4_fixedDiscountCappedBySubtotal() {
        String code = couponWith("FIXED", 50_000, null, null, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 20_000, 0);
    }

    @Test
    @DisplayName("R2.4 RATE 는 floor(subtotal*value/100) 이다 (9999 * 10% = 999)")
    void r2_4_rateDiscountFloors() {
        String code = couponWith("RATE", 10, null, null, 10);

        assertAmounts(orderWith(code, 3333, 3), 9999, 999, 9000);
    }

    @Test
    @DisplayName("R2.4 RATE 1% 에서 1원 미만은 0 으로 내림된다")
    void r2_4_rateBelowOneWonFloorsToZero() {
        String code = couponWith("RATE", 1, null, null, 10);

        assertAmounts(orderWith(code, 99, 1), 99, 0, 99);
    }

    @Test
    @DisplayName("R2.4 RATE 100% 는 subtotal 전액 할인")
    void r2_4_rate100PercentDiscountsEverything() {
        String code = couponWith("RATE", 100, null, null, 10);

        assertAmounts(orderWith(code, 777, 1), 777, 777, 0);
    }

    @Test
    @DisplayName("R2.4 RATE 할인은 maxDiscountAmount 로 상한된다")
    void r2_4_rateCappedByMaxDiscount() {
        String code = couponWith("RATE", 50, null, 4000L, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 4000, 16_000);
    }

    @Test
    @DisplayName("R2.4 FIXED 도 maxDiscountAmount 로 상한된다")
    void r2_4_fixedCappedByMaxDiscount() {
        String code = couponWith("FIXED", 9000, null, 2500L, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 2500, 17_500);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 가 계산 할인보다 크면 영향이 없다")
    void r2_4_maxDiscountAboveComputedHasNoEffect() {
        String code = couponWith("RATE", 10, null, 1_000_000L, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 2000, 18_000);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 상한 후 subtotal 상한도 적용된다 (FIXED 50000, max 40000, subtotal 20000)")
    void r2_4_maxThenSubtotalCap() {
        String code = couponWith("FIXED", 50_000, null, 40_000L, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 20_000, 0);
    }

    @Test
    @DisplayName("R2.4 subtotal 은 여러 항목의 unitPrice*quantity 합이다")
    void r2_4_subtotalSumsAllLines() {
        long a = newProduct(1500, 100);
        long b = newProduct(700, 100);
        String code = couponWith("RATE", 10, null, null, 10);

        JsonNode order = newOrder(uniqueUser(), code, items(a, 2, b, 3));

        assertAmounts(order, 5100, 510, 4590);
    }

    @Test
    @DisplayName("C1 subtotal 이 int 를 넘는 주문에서도 RATE 할인·totalPrice 가 정확하다")
    void c1_rateDiscountBeyondIntRange() {
        String code = couponWith("RATE", 33, null, null, 10);

        JsonNode order = orderWith(code, 10_000_000L, 1000);

        assertAmounts(order, 10_000_000_000L, 3_300_000_000L, 6_700_000_000L);
    }

    @Test
    @DisplayName("C1 int 를 넘는 FIXED value 와 maxDiscountAmount 가 계산에 쓰인다")
    void c1_fixedAndMaxBeyondIntRange() {
        String fixed = couponWith("FIXED", 5_000_000_000L, null, null, 10);
        String capped = couponWith("FIXED", 5_000_000_000L, null, 4_000_000_000L, 10);

        assertAmounts(orderWith(fixed, 10_000_000L, 1000), 10_000_000_000L, 5_000_000_000L, 5_000_000_000L);
        assertAmounts(orderWith(capped, 10_000_000L, 1000), 10_000_000_000L, 4_000_000_000L, 6_000_000_000L);
    }

    // ------------------------------------------------------------------ R2.5 적용 조건

    @Test
    @DisplayName("R2.5 subtotal == minOrderAmount 이면 쿠폰이 적용된다")
    void r2_5_subtotalEqualToMinimumAccepted() {
        String code = couponWith("FIXED", 1000, 20_000L, null, 10);

        assertAmounts(orderWith(code, 10_000, 2), 20_000, 1000, 19_000);
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount 이면 409 COUPON_NOT_APPLICABLE, usedCount·예약 불변")
    void r2_5_subtotalBelowMinimumRejected() {
        String code = couponWith("FIXED", 1000, 20_001L, null, 10);
        long productId = newProduct(10_000, 10);

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), code, productId, 2);

        assertProblem(res, 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 0);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.5 minOrderAmount 는 할인 전 subtotal 기준이다 (int 초과 minOrderAmount 포함)")
    void r2_5_minimumUsesSubtotalBeforeDiscount() {
        String code = couponWith("FIXED", 1000, 10_000_000_000L, null, 10);
        long productId = newProduct(10_000_000L, 2000);

        assertAmounts(newOrder(uniqueUser(), code, items(productId, 1000)), 10_000_000_000L, 1000, 9_999_999_000L);
        assertProblem(placeOrder(uniqueUser(), code, productId, 999), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 validUntil 이 이미 지난 쿠폰은 409 COUPON_NOT_APPLICABLE")
    void r2_5_expiredCouponRejected() {
        Instant now = Instant.now();
        Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 1000);
        body.put("validFrom", now.minus(2, ChronoUnit.HOURS).toString());
        body.put("validUntil", now.minus(1, ChronoUnit.HOURS).toString());
        String code = createCoupon(body).get("code").asText();
        long productId = newProduct(1000, 10);

        assertProblem(placeOrder(uniqueUser(), code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 0);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.5 validFrom 이 아직 오지 않은 쿠폰은 409 COUPON_NOT_APPLICABLE")
    void r2_5_notYetValidCouponRejected() {
        Instant now = Instant.now();
        Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 1000);
        body.put("validFrom", now.plus(1, ChronoUnit.HOURS).toString());
        body.put("validUntil", now.plus(2, ChronoUnit.HOURS).toString());
        String code = createCoupon(body).get("code").asText();
        long productId = newProduct(1000, 10);

        assertProblem(placeOrder(uniqueUser(), code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 0);
        assertStock(productId, 10, 0);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 사용 중인 주문이 있으면 409 COUPON_NOT_APPLICABLE, 다른 사용자는 가능")
    void r2_5_sameUserInUseRejectedOtherUserAllowed() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        newOrder(user, code, items(productId, 1));

        ResponseEntity<JsonNode> again = placeOrder(user, code, productId, 1);

        assertProblem(again, 409, "COUPON_NOT_APPLICABLE");
        assertUsedCount(code, 1);
        assertStock(productId, 100, 1);
        assertThat(code(placeOrder(uniqueUser(), code, productId, 1))).isEqualTo(201);
        assertUsedCount(code, 2);
    }

    @Test
    @DisplayName("R2.5 같은 사용자라도 쿠폰 없는 주문은 계속 만들 수 있다")
    void r2_5_sameUserCanOrderWithoutCoupon() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        newOrder(user, code, items(productId, 1));

        assertThat(code(placeOrder(user, null, productId, 1))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity 이면 409 COUPON_EXHAUSTED, 예약·usedCount 불변")
    void r2_5_exhaustedCouponRejected() {
        String code = couponWith("FIXED", 1000, null, null, 1);
        long productId = newProduct(5000, 10);
        newOrder(uniqueUser(), code, items(productId, 1));

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), code, productId, 1);

        assertProblem(res, 409, "COUPON_EXHAUSTED");
        assertUsedCount(code, 1);
        assertStock(productId, 10, 1);
    }

    // ------------------------------------------------------------------ R2.6 usedCount 와 복원

    @Test
    @DisplayName("R2.6 주문 생성 시 usedCount 가 1 늘고 PAID·SHIPPED·DELIVERED 에서도 유지되며 같은 사용자는 재사용할 수 없다")
    void r2_6_usedCountStaysWhileOrderIsInUse() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();
        assertUsedCount(code, 1);

        payOk(orderId);
        assertUsedCount(code, 1);
        assertProblem(placeOrder(user, code, productId, 1), 409, "COUPON_NOT_APPLICABLE");

        assertStatus(ship(orderId), 200);
        assertUsedCount(code, 1);
        assertProblem(placeOrder(user, code, productId, 1), 409, "COUPON_NOT_APPLICABLE");

        assertStatus(deliver(orderId), 200);
        assertUsedCount(code, 1);
        assertProblem(placeOrder(user, code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.6 CANCELLED: usedCount 1 감소 + 같은 사용자 재사용 가능")
    void r2_6_restoredOnCancelled() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();

        assertStatus(cancel(orderId), 200);

        assertUsedCount(code, 0);
        assertThat(code(placeOrder(user, code, productId, 1))).isEqualTo(201);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R2.6 PAYMENT_FAILED(결제 거절): usedCount 1 감소 + 같은 사용자 재사용 가능")
    void r2_6_restoredOnPaymentFailed() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();

        assertProblem(pay(orderId, uniqueKey(), "decline_card"), 402, "PAYMENT_DECLINED");

        assertThat(statusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        assertUsedCount(code, 0);
        assertThat(code(placeOrder(user, code, productId, 1))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 REFUNDED(PAID 취소): usedCount 1 감소 + 같은 사용자 재사용 가능")
    void r2_6_restoredOnRefunded() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();
        payOk(orderId);

        assertStatus(cancel(orderId), 200);

        assertThat(statusOf(orderId)).isEqualTo("REFUNDED");
        assertUsedCount(code, 0);
        assertThat(code(placeOrder(user, code, productId, 1))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 복원된 수량은 소진된 쿠폰을 다른 사용자가 다시 쓸 수 있게 한다")
    void r2_6_restoredQuantityReleasesExhaustedCoupon() {
        String code = couponWith("FIXED", 1000, null, null, 1);
        long productId = newProduct(5000, 10);
        long first = newOrder(uniqueUser(), code, items(productId, 1)).get("id").asLong();
        assertProblem(placeOrder(uniqueUser(), code, productId, 1), 409, "COUPON_EXHAUSTED");

        assertStatus(cancel(first), 200);

        assertThat(code(placeOrder(uniqueUser(), code, productId, 1))).isEqualTo(201);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R2.6 PG 장애(503)로 끝난 결제는 usedCount 를 바꾸지 않고, 쿠폰은 계속 사용 중이다")
    void r2_6_notRestoredOnGatewayFailure() {
        String code = couponWith("FIXED", 1000, null, null, 10);
        long productId = newProduct(5000, 100);
        String user = uniqueUser();
        long orderId = newOrder(user, code, items(productId, 1)).get("id").asLong();
        PG.respondWith(r -> FakePaymentGateway.Response.status(500));

        assertProblem(pay(orderId), 503, "PAYMENT_GATEWAY_UNAVAILABLE");

        assertUsedCount(code, 1);
        assertProblem(placeOrder(user, code, productId, 1), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.6 실패한 주문 생성(재고 부족)은 usedCount 를 올리지 않는다")
    void r2_6_failedCreationDoesNotConsumeCoupon() {
        String code = couponWith("FIXED", 1000, null, null, 1);
        long productId = newProduct(5000, 1);

        assertProblem(placeOrder(uniqueUser(), code, productId, 2), 409, "INSUFFICIENT_STOCK");

        assertUsedCount(code, 0);
        assertThat(code(placeOrder(uniqueUser(), code, productId, 1))).isEqualTo(201);
    }
}
