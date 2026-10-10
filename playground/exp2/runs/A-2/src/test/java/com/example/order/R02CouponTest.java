package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R2 쿠폰")
class R02CouponTest extends IntegrationTest {

    @Test
    @DisplayName("R2.1·R2.3 등록하면 201 + Location, usedCount 0, 생략한 값은 기본값")
    void createCoupon() {
        Map<String, Object> body = couponBody("WELCOME10", "RATE", 10);
        body.remove("minOrderAmount");
        body.remove("maxDiscountAmount");

        Resp resp = api.post("/api/coupons", body);

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.header("Location")).isEqualTo("/api/coupons/WELCOME10");
        JsonNode json = resp.json();
        assertThat(json.path("code").asText()).isEqualTo("WELCOME10");
        assertThat(json.path("type").asText()).isEqualTo("RATE");
        assertThat(json.path("value").asLong()).isEqualTo(10);
        assertThat(json.path("minOrderAmount").asLong()).isZero();
        assertThat(json.has("maxDiscountAmount")).isTrue();
        assertThat(json.get("maxDiscountAmount").isNull()).isTrue();
        assertThat(json.path("totalQuantity").asInt()).isEqualTo(100);
        assertThat(json.path("usedCount").asInt()).isZero();
        assertThat(OffsetDateTime.parse(json.path("validFrom").asText()).toInstant())
                .isEqualTo(OffsetDateTime.parse((String) body.get("validFrom")).toInstant());
        assertThat(OffsetDateTime.parse(json.path("validUntil").asText()).toInstant())
                .isEqualTo(OffsetDateTime.parse((String) body.get("validUntil")).toInstant());
        assertThat(coupon("WELCOME10")).isEqualTo(json);
    }

    @Test
    @DisplayName("R2.2 규칙을 어기면 400")
    void invalidCoupons() {
        assertInvalid(b -> b.put("code", "abcd"));
        assertInvalid(b -> b.put("code", "ABC"));
        assertInvalid(b -> b.put("code", "A".repeat(21)));
        assertInvalid(b -> b.put("code", "AB-12"));
        assertInvalid(b -> b.remove("code"));
        assertInvalid(b -> b.put("type", "PERCENT"));
        assertInvalid(b -> b.remove("type"));
        assertInvalid(b -> b.put("value", 0));
        assertInvalid(b -> {
            b.put("type", "RATE");
            b.put("value", 101);
        });
        assertInvalid(b -> {
            b.put("type", "RATE");
            b.put("value", 0);
        });
        assertInvalid(b -> b.put("minOrderAmount", -1));
        assertInvalid(b -> b.put("maxDiscountAmount", 0));
        assertInvalid(b -> b.put("totalQuantity", 0));
        assertInvalid(b -> b.remove("totalQuantity"));
        assertInvalid(b -> b.put("validUntil", b.get("validFrom")));
        assertInvalid(b -> {
            Object from = b.get("validFrom");
            b.put("validFrom", b.get("validUntil"));
            b.put("validUntil", from);
        });
        assertInvalid(b -> b.put("validFrom", "2026-01-01T00:00:00")); // 오프셋 없음
        assertInvalid(b -> b.remove("validUntil"));
    }

    @Test
    @DisplayName("R2.2 경계값(4자·20자 코드, RATE 100%, FIXED 큰 값)은 허용")
    void boundariesAccepted() {
        createCoupon(couponBody("AB12", "RATE", 100));
        createCoupon(couponBody("A".repeat(20), "FIXED", 5_000_000_000L));
        assertThat(coupon("A".repeat(20)).path("value").asLong()).isEqualTo(5_000_000_000L);
    }

    @Test
    @DisplayName("R2.2 이미 있는 code면 409")
    void duplicateCode() {
        createCoupon(couponBody("DUP1", "FIXED", 1000));
        assertProblem(api.post("/api/coupons", couponBody("DUP1", "RATE", 5)), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰 조회는 404")
    void couponNotFound() {
        assertProblem(api.get("/api/coupons/NOPE"), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.4 FIXED 할인")
    void fixedDiscount() {
        long p = createProduct(5000, 10);
        createCoupon(couponBody("FIX3000", "FIXED", 3000));

        JsonNode order = placeOrder("u1", "FIX3000", p, 2).json();

        assertThat(order.path("subtotal").asLong()).isEqualTo(10000);
        assertThat(order.path("discount").asLong()).isEqualTo(3000);
        assertThat(order.path("totalPrice").asLong()).isEqualTo(7000);
        assertThat(order.path("couponCode").asText()).isEqualTo("FIX3000");
    }

    @Test
    @DisplayName("R2.4 RATE 할인은 내림")
    void rateDiscountFloors() {
        long p = createProduct(3333, 10);
        createCoupon(couponBody("RATE15", "RATE", 15));

        JsonNode order = placeOrder("u1", "RATE15", p, 3).json(); // 9999 * 15% = 1499.85

        assertThat(order.path("subtotal").asLong()).isEqualTo(9999);
        assertThat(order.path("discount").asLong()).isEqualTo(1499);
        assertThat(order.path("totalPrice").asLong()).isEqualTo(8500);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 상한, 그다음 subtotal 상한")
    void discountCaps() {
        long p = createProduct(10000, 10);
        Map<String, Object> capped = couponBody("RATE50MAX", "RATE", 50);
        capped.put("maxDiscountAmount", 2000);
        createCoupon(capped);
        createCoupon(couponBody("BIGFIXED", "FIXED", 50000));

        JsonNode cappedOrder = placeOrder("u1", "RATE50MAX", p, 1).json();
        assertThat(cappedOrder.path("discount").asLong()).isEqualTo(2000);
        assertThat(cappedOrder.path("totalPrice").asLong()).isEqualTo(8000);

        JsonNode fullOrder = placeOrder("u1", "BIGFIXED", p, 2).json();
        assertThat(fullOrder.path("subtotal").asLong()).isEqualTo(20000);
        assertThat(fullOrder.path("discount").asLong()).isEqualTo(20000);
        assertThat(fullOrder.path("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.5 유효 기간 밖이면 409 COUPON_NOT_APPLICABLE (validFrom 포함, validUntil 제외)")
    void outsideValidityWindow() {
        long p = createProduct(1000, 10);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> future = couponBody("FUTURE", "FIXED", 100);
        future.put("validFrom", now.plusDays(1).toString());
        future.put("validUntil", now.plusDays(2).toString());
        createCoupon(future);
        Map<String, Object> past = couponBody("PAST", "FIXED", 100);
        past.put("validFrom", now.minusDays(2).toString());
        past.put("validUntil", now.minusSeconds(1).toString());
        createCoupon(past);

        assertProblem(placeOrder("u1", "FUTURE", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertProblem(placeOrder("u1", "PAST", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(product(p).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R2.5 subtotal이 minOrderAmount 미만이면 409, 같으면 허용")
    void minOrderAmount() {
        long p = createProduct(1000, 10);
        Map<String, Object> body = couponBody("MIN3000", "FIXED", 500);
        body.put("minOrderAmount", 3000);
        createCoupon(body);

        assertProblem(placeOrder("u1", "MIN3000", p, 2), 409, "COUPON_NOT_APPLICABLE");
        assertThat(placeOrder("u1", "MIN3000", p, 3).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이미 사용 중이면 409, 다른 사용자는 가능")
    void sameUserCannotReuseWhileInUse() {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("ONCE", "FIXED", 100));

        placeOrderOk("u1", "ONCE", p, 1);
        assertProblem(placeOrder("u1", "ONCE", p, 1), 409, "COUPON_NOT_APPLICABLE");
        assertThat(placeOrder("u2", "ONCE", p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 usedCount = totalQuantity면 409 COUPON_EXHAUSTED")
    void exhausted() {
        long p = createProduct(1000, 10);
        Map<String, Object> body = couponBody("LIMIT2", "FIXED", 100);
        body.put("totalQuantity", 2);
        createCoupon(body);

        placeOrderOk("u1", "LIMIT2", p, 1);
        placeOrderOk("u2", "LIMIT2", p, 1);
        assertProblem(placeOrder("u3", "LIMIT2", p, 1), 409, "COUPON_EXHAUSTED");
        assertThat(coupon("LIMIT2").path("usedCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.6 사용 중인 주문 수가 usedCount, 취소되면 복원되어 같은 사용자가 다시 사용 가능")
    void usageRestoredOnCancel() {
        long p = createProduct(1000, 10);
        Map<String, Object> body = couponBody("RESTORE", "FIXED", 100);
        body.put("totalQuantity", 1);
        createCoupon(body);

        long orderId = placeOrderOk("u1", "RESTORE", p, 1);
        assertThat(coupon("RESTORE").path("usedCount").asInt()).isEqualTo(1);

        assertThat(api.post("/api/orders/" + orderId + "/cancel", null).status()).isEqualTo(200);
        assertThat(coupon("RESTORE").path("usedCount").asInt()).isZero();
        assertThat(placeOrder("u1", "RESTORE", p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 결제 완료·배송된 주문은 계속 사용 중으로 남는다")
    void usageKeptWhilePaidOrDelivered() {
        long p = createProduct(1000, 10);
        createCoupon(couponBody("KEEP", "FIXED", 100));

        long orderId = paidOrder("u1", "KEEP", p, 1);
        api.post("/api/orders/" + orderId + "/ship", null);
        api.post("/api/orders/" + orderId + "/deliver", null);

        assertThat(coupon("KEEP").path("usedCount").asInt()).isEqualTo(1);
        assertProblem(placeOrder("u1", "KEEP", p, 1), 409, "COUPON_NOT_APPLICABLE");
    }

    private void assertInvalid(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> body = couponBody("VALID1", "FIXED", 1000);
        mutation.accept(body);
        assertProblem(api.post("/api/coupons", body), 400, "VALIDATION_ERROR");
    }
}
