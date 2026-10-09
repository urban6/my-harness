package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api;
import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R2 쿠폰")
class CouponApiTest extends IntegrationTest {

    @Test
    @DisplayName("R2.1/R2.3 등록하면 201 + Location, 조회 결과와 같은 본문 (usedCount=0)")
    void createAndGet() {
        String code = uniqueCouponCode();
        Response created = api.post("/api/coupons", """
                {"code":"%s","type":"RATE","value":10,"minOrderAmount":5000,"maxDiscountAmount":3000,
                 "totalQuantity":50,"validFrom":"2026-01-01T00:00:00+09:00","validUntil":"2099-12-31T23:59:59+09:00"}
                """.formatted(code));

        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.location()).endsWith("/api/coupons/" + code);
        JsonNode body = created.body();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).isEqualTo("RATE");
        assertThat(body.get("value").asLong()).isEqualTo(10);
        assertThat(body.get("minOrderAmount").asLong()).isEqualTo(5000);
        assertThat(body.get("maxDiscountAmount").asLong()).isEqualTo(3000);
        assertThat(body.get("totalQuantity").asLong()).isEqualTo(50);
        assertThat(body.get("usedCount").asLong()).isZero();
        assertThat(OffsetDateTime.parse(body.get("validFrom").asText()))
                .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+09:00"));
        assertThat(OffsetDateTime.parse(body.get("validUntil").asText()))
                .isEqualTo(OffsetDateTime.parse("2099-12-31T23:59:59+09:00"));

        assertThat(api.get("/api/coupons/" + code).body()).isEqualTo(body);
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 생략 시 0, maxDiscountAmount 생략 시 제한 없음(null)")
    void defaults() {
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 1000);
        body.remove("minOrderAmount");
        JsonNode created = coupon(createCoupon(body));
        assertThat(created.get("minOrderAmount").asLong()).isZero();
        assertThat(created.get("maxDiscountAmount").isNull()).isTrue();
    }

    static Stream<Arguments> invalidCoupons() {
        return Stream.of(
                invalid("code 소문자", b -> b.put("code", "abcd1234")),
                invalid("code 3자", b -> b.put("code", "AB1")),
                invalid("code 21자", b -> b.put("code", "A".repeat(21))),
                invalid("code 누락", b -> b.remove("code")),
                invalid("type 알 수 없음", b -> b.put("type", "PERCENT")),
                invalid("FIXED value 0", b -> b.put("value", 0)),
                invalid("RATE value 101", b -> {
                    b.put("type", "RATE");
                    b.put("value", 101);
                }),
                invalid("RATE value 0", b -> {
                    b.put("type", "RATE");
                    b.put("value", 0);
                }),
                invalid("minOrderAmount 음수", b -> b.put("minOrderAmount", -1)),
                invalid("maxDiscountAmount 0", b -> b.put("maxDiscountAmount", 0)),
                invalid("totalQuantity 0", b -> b.put("totalQuantity", 0)),
                invalid("validFrom == validUntil", b -> {
                    b.put("validFrom", "2030-01-01T00:00:00Z");
                    b.put("validUntil", "2030-01-01T09:00:00+09:00");
                }),
                invalid("validFrom > validUntil", b -> {
                    b.put("validFrom", "2030-01-02T00:00:00Z");
                    b.put("validUntil", "2030-01-01T00:00:00Z");
                }),
                invalid("오프셋 없는 시각", b -> b.put("validFrom", "2020-01-01T00:00:00")));
    }

    private static Arguments invalid(String name, Consumer<Map<String, Object>> mutation) {
        return Arguments.of(name, mutation);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCoupons")
    @DisplayName("R2.2 규칙 위반은 400")
    void rejectInvalid(String name, Consumer<Map<String, Object>> mutation) {
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 1000);
        mutation.accept(body);
        assertProblem(api.post("/api/coupons", Api.json(body)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 RATE value 1과 100은 허용")
    void rateBoundaries() {
        createCoupon(couponBody(uniqueCouponCode(), "RATE", 1));
        createCoupon(couponBody(uniqueCouponCode(), "RATE", 100));
    }

    @Test
    @DisplayName("R2.2 이미 있는 code는 409 DUPLICATE_COUPON_CODE")
    void duplicateCode() {
        String code = createCoupon("FIXED", 1000, 10);
        assertProblem(api.post("/api/coupons", Api.json(couponBody(code, "FIXED", 500))), 409,
                "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void getMissing() {
        assertProblem(api.get("/api/coupons/NOPE0000"), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.4 RATE 할인은 내림, maxDiscountAmount 상한, subtotal 상한")
    void discountCalculation() {
        long p = createProduct(999, 100);

        String rate = createCoupon("RATE", 15, 10);
        JsonNode floor = createOrder(uniqueUser(), rate, p, 1);   // 999 * 15 / 100 = 149.85
        assertThat(floor.get("subtotal").asLong()).isEqualTo(999);
        assertThat(floor.get("discount").asLong()).isEqualTo(149);
        assertThat(floor.get("totalPrice").asLong()).isEqualTo(850);

        Map<String, Object> capped = couponBody(uniqueCouponCode(), "RATE", 50);
        capped.put("maxDiscountAmount", 1000);
        JsonNode cap = createOrder(uniqueUser(), createCoupon(capped), p, 10); // 9990 * 50% = 4995 → 1000
        assertThat(cap.get("discount").asLong()).isEqualTo(1000);
        assertThat(cap.get("totalPrice").asLong()).isEqualTo(8990);

        String fixed = createCoupon("FIXED", 5000, 10);
        JsonNode overSubtotal = createOrder(uniqueUser(), fixed, p, 2);   // 1998 < 5000
        assertThat(overSubtotal.get("discount").asLong()).isEqualTo(1998);
        assertThat(overSubtotal.get("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.5 유효 기간 밖이면 409 COUPON_NOT_APPLICABLE")
    void outsideValidity() {
        long p = createProduct(1000, 10);
        Map<String, Object> notYet = couponBody(uniqueCouponCode(), "FIXED", 100);
        notYet.put("validFrom", OffsetDateTime.now().plusHours(1).toString());
        notYet.put("validUntil", OffsetDateTime.now().plusHours(2).toString());
        Map<String, Object> ended = couponBody(uniqueCouponCode(), "FIXED", 100);
        ended.put("validFrom", OffsetDateTime.now().minusHours(2).toString());
        ended.put("validUntil", OffsetDateTime.now().minusSeconds(1).toString());

        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(createCoupon(notYet), p, 1)), 409,
                "COUPON_NOT_APPLICABLE");
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(createCoupon(ended), p, 1)), 409,
                "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount면 409 COUPON_NOT_APPLICABLE, 같으면 허용")
    void minOrderAmount() {
        long p = createProduct(1000, 10);
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
        body.put("minOrderAmount", 3000);
        String code = createCoupon(body);

        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 2)), 409, "COUPON_NOT_APPLICABLE");
        assertThat(postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 3)).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 사용 중인 쿠폰은 409 COUPON_NOT_APPLICABLE (다른 사용자는 가능)")
    void alreadyInUseBySameUser() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 10);
        String user = uniqueUser();
        createOrder(user, code, p, 1);

        assertProblem(postOrder(user, uniqueKey(), orderBody(code, p, 1)), 409, "COUPON_NOT_APPLICABLE");
        createOrder(uniqueUser(), code, p, 1);
    }

    @Test
    @DisplayName("R2.5 usedCount = totalQuantity면 409 COUPON_EXHAUSTED")
    void exhausted() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 1);
        createOrder(uniqueUser(), code, p, 1);
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 1)), 409, "COUPON_EXHAUSTED");
    }

    @Test
    @DisplayName("R2.6 취소·거절·환불되면 usedCount가 줄고 같은 사용자가 다시 쓸 수 있다")
    void usageRestored() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 1);
        String user = uniqueUser();

        long cancelled = createOrder(user, code, p, 1).get("id").asLong();
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
        assertThat(api.post("/api/orders/" + cancelled + "/cancel", null).status()).isEqualTo(200);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();

        long declined = createOrder(user, code, p, 1).get("id").asLong();
        assertProblem(pay(declined, uniqueKey(), "decline"), 402, "PAYMENT_DECLINED");
        assertThat(coupon(code).get("usedCount").asLong()).isZero();

        long refunded = paidOrder(user, code, "tok", p, 1).get("id").asLong();
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
        assertThat(api.post("/api/orders/" + refunded + "/cancel", null).status()).isEqualTo(200);
        assertThat(coupon(code).get("usedCount").asLong()).isZero();

        createOrder(user, code, p, 1);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 결제 완료·배송된 주문은 계속 쿠폰을 사용 중이다")
    void paidOrderKeepsUsage() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 5);
        String user = uniqueUser();
        long orderId = paidOrder(user, code, "tok", p, 1).get("id").asLong();
        api.post("/api/orders/" + orderId + "/ship", null);
        api.post("/api/orders/" + orderId + "/deliver", null);

        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
        assertProblem(postOrder(user, uniqueKey(), orderBody(code, p, 1)), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.2 type은 대문자 FIXED/RATE만 허용")
    void typeCaseSensitive() {
        Map<String, Object> body = couponBody(uniqueCouponCode(), "fixed", 100);
        assertProblem(api.post("/api/coupons", Api.json(body)), 400, "VALIDATION_ERROR");
    }
}
