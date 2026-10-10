package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R2. 쿠폰 */
class CouponApiTest extends IntegrationTestSupport {

    @Test
    void create_returns201WithLocationAndBody() {
        Map<String, Object> body = couponBody(Map.of(
                "type", "RATE", "value", 10, "maxDiscountAmount", 5_000, "totalQuantity", 3,
                "validFrom", "2026-01-01T09:00:00+09:00", "validUntil", "2027-01-01T00:00:00Z"));
        body.remove("minOrderAmount");

        Res res = post("/api/coupons", body, Map.of());

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.headers().getLocation().getPath()).isEqualTo("/api/coupons/" + body.get("code"));
        JsonNode coupon = res.body();
        assertThat(coupon.get("code").asText()).isEqualTo(body.get("code"));
        assertThat(coupon.get("type").asText()).isEqualTo("RATE");
        assertThat(coupon.get("value").asLong()).isEqualTo(10);
        assertThat(coupon.get("minOrderAmount").asLong()).isZero();
        assertThat(coupon.get("maxDiscountAmount").asLong()).isEqualTo(5_000);
        assertThat(coupon.get("totalQuantity").asInt()).isEqualTo(3);
        assertThat(coupon.get("usedCount").asInt()).isZero();
        assertThat(OffsetDateTime.parse(coupon.get("validFrom").asText()).toInstant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(OffsetDateTime.parse(coupon.get("validUntil").asText()).toInstant())
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(get("/api/coupons/" + body.get("code")).body()).isEqualTo(coupon);
    }

    @Test
    void create_withoutMaxDiscount_returnsNull() {
        String code = createCoupon(Map.of());

        assertThat(coupon(code).get("maxDiscountAmount").isNull()).isTrue();
    }

    static Stream<Arguments> invalidCoupons() {
        return Stream.of(
                Arguments.of("code", "lower123"),
                Arguments.of("code", "ABC"),
                Arguments.of("code", "A".repeat(21)),
                Arguments.of("code", "AB-12"),
                Arguments.of("code", null),
                Arguments.of("type", "PERCENT"),
                Arguments.of("type", null),
                Arguments.of("value", 0),
                Arguments.of("value", null),
                Arguments.of("minOrderAmount", -1),
                Arguments.of("maxDiscountAmount", 0),
                Arguments.of("totalQuantity", 0),
                Arguments.of("totalQuantity", null),
                Arguments.of("validFrom", "2026-01-01T00:00:00"),
                Arguments.of("validUntil", null));
    }

    @ParameterizedTest
    @MethodSource("invalidCoupons")
    void create_invalidField_returns400(String field, Object value) {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(field, value);

        assertProblem(post("/api/coupons", couponBody(overrides), Map.of()), 400, "VALIDATION_ERROR");
    }

    @Test
    void create_rateOutOfRange_returns400() {
        assertProblem(post("/api/coupons", couponBody(Map.of("type", "RATE", "value", 0)), Map.of()), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", couponBody(Map.of("type", "RATE", "value", 101)), Map.of()), 400, "VALIDATION_ERROR");
        assertThat(post("/api/coupons", couponBody(Map.of("type", "RATE", "value", 100)), Map.of()).status()).isEqualTo(201);
    }

    @Test
    void create_validFromNotBeforeValidUntil_returns400() {
        String t = "2026-05-01T00:00:00Z";
        assertProblem(post("/api/coupons", couponBody(Map.of("validFrom", t, "validUntil", t)), Map.of()), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/coupons", couponBody(Map.of("validFrom", "2026-06-01T00:00:00Z", "validUntil", t)), Map.of()),
                400, "VALIDATION_ERROR");
    }

    @Test
    void create_duplicateCode_returns409() {
        String code = createCoupon(Map.of());

        assertProblem(post("/api/coupons", couponBody(Map.of("code", code)), Map.of()), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    void get_unknown_returns404() {
        assertProblem(get("/api/coupons/NOPE0000"), 404, "COUPON_NOT_FOUND");
    }

    // ---------- R2.4 할인액 ----------

    @Test
    void discount_fixed() {
        long p = createProduct(5_000, 10);
        String code = createCoupon(Map.of("type", "FIXED", "value", 3_000));

        JsonNode order = createOrder(uniqueUser(), code, item(p, 2)).body();

        assertThat(order.get("subtotal").asLong()).isEqualTo(10_000);
        assertThat(order.get("discount").asLong()).isEqualTo(3_000);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(7_000);
        assertThat(order.get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    void discount_rateIsFloored() {
        long p = createProduct(9_999, 10);
        String code = createCoupon(Map.of("type", "RATE", "value", 15));

        JsonNode order = createOrder(uniqueUser(), code, item(p, 1)).body();

        assertThat(order.get("discount").asLong()).isEqualTo(1_499); // floor(1499.85)
        assertThat(order.get("totalPrice").asLong()).isEqualTo(8_500);
    }

    @Test
    void discount_cappedByMaxDiscountAmount() {
        long p = createProduct(100_000, 10);
        String code = createCoupon(Map.of("type", "RATE", "value", 50, "maxDiscountAmount", 7_000));

        JsonNode order = createOrder(uniqueUser(), code, item(p, 1)).body();

        assertThat(order.get("discount").asLong()).isEqualTo(7_000);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(93_000);
    }

    @Test
    void discount_cappedBySubtotal() {
        long p = createProduct(4_000, 10);
        String code = createCoupon(Map.of("type", "FIXED", "value", 50_000));

        JsonNode order = createOrder(uniqueUser(), code, item(p, 1)).body();

        assertThat(order.get("discount").asLong()).isEqualTo(4_000);
        assertThat(order.get("totalPrice").asLong()).isZero();
    }

    @Test
    void discount_handlesAmountsBeyondIntRange() {
        long p1 = createProduct(10_000_000, 1_000_000);
        String code = createCoupon(Map.of("type", "RATE", "value", 10));

        JsonNode order = createOrder(uniqueUser(), code, item(p1, 1_000)).body();

        assertThat(order.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(order.get("discount").asLong()).isEqualTo(1_000_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(9_000_000_000L);
    }

    // ---------- R2.5 적용 조건 ----------

    @Test
    void notYetValid_returns409NotApplicable() {
        long p = createProduct(10_000, 10);
        Instant now = Instant.now();
        String code = createCoupon(Map.of(
                "validFrom", now.plus(1, ChronoUnit.HOURS).toString(),
                "validUntil", now.plus(2, ChronoUnit.HOURS).toString()));

        assertProblem(createOrder(uniqueUser(), code, item(p, 1)), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    void expired_returns409NotApplicable() {
        long p = createProduct(10_000, 10);
        Instant now = Instant.now();
        String code = createCoupon(Map.of(
                "validFrom", now.minus(2, ChronoUnit.HOURS).toString(),
                "validUntil", now.minus(1, ChronoUnit.SECONDS).toString()));

        assertProblem(createOrder(uniqueUser(), code, item(p, 1)), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    void belowMinOrderAmount_returns409NotApplicable_butEqualIsAllowed() {
        long p = createProduct(10_000, 10);
        String code = createCoupon(Map.of("minOrderAmount", 20_000));

        assertProblem(createOrder(uniqueUser(), code, item(p, 1)), 409, "COUPON_NOT_APPLICABLE");
        assertThat(createOrder(uniqueUser(), code, item(p, 2)).status()).isEqualTo(201);
    }

    @Test
    void sameUserWithActiveOrder_returns409NotApplicable() {
        long p = createProduct(10_000, 10);
        String code = createCoupon(Map.of());
        String user = uniqueUser();
        placeOrder(user, code, item(p, 1));

        assertProblem(createOrder(user, code, item(p, 1)), 409, "COUPON_NOT_APPLICABLE");
        assertThat(createOrder(uniqueUser(), code, item(p, 1)).status()).isEqualTo(201);
    }

    @Test
    void sameUserWithDeliveredOrder_isStillUsingCoupon() {
        long p = createProduct(10_000, 10);
        String code = createCoupon(Map.of());
        String user = uniqueUser();
        long orderId = paidOrder(user, code, item(p, 1));
        post("/api/orders/" + orderId + "/ship");
        post("/api/orders/" + orderId + "/deliver");

        assertProblem(createOrder(user, code, item(p, 1)), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    void exhausted_returns409Exhausted() {
        long p = createProduct(10_000, 10);
        String code = createCoupon(Map.of("totalQuantity", 1));
        placeOrder(uniqueUser(), code, item(p, 1));

        assertProblem(createOrder(uniqueUser(), code, item(p, 1)), 409, "COUPON_EXHAUSTED");
    }

    // ---------- R2.6 사용 수 ----------

    @Test
    void usedCount_increasesOnOrder_andIsRestoredOnCancel() {
        long p = createProduct(10_000, 10);
        String code = createCoupon(Map.of("totalQuantity", 1));
        String user = uniqueUser();
        long orderId = placeOrder(user, code, item(p, 1));
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

        assertThat(post("/api/orders/" + orderId + "/cancel").status()).isEqualTo(200);

        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(createOrder(user, code, item(p, 1)).status()).isEqualTo(201);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }
}
