package com.example.order;

import static com.example.order.support.TestApi.coupon;
import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newCouponCode;
import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway.Mode;
import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
    @DisplayName("R2.1 쿠폰 등록 → 201 + Location, usedCount=0, 생략 필드 기본값")
    void create_returns201() {
        String code = newCouponCode();
        Map<String, Object> request = coupon(code, "RATE", 10);
        request.put("maxDiscountAmount", 5000);

        Response response = api.post("/api/coupons", request).assertStatus(201);

        JsonNode body = response.body();
        assertThat(response.header("Location")).endsWith("/api/coupons/" + code);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).isEqualTo("RATE");
        assertThat(body.get("value").asLong()).isEqualTo(10);
        assertThat(body.get("minOrderAmount").asLong()).isZero();
        assertThat(body.get("maxDiscountAmount").asLong()).isEqualTo(5000);
        assertThat(body.get("totalQuantity").asInt()).isEqualTo(100);
        assertThat(body.get("usedCount").asInt()).isZero();
        assertThat(Instant.parse(body.get("validFrom").asText()))
                .isEqualTo(OffsetDateTime.parse((String) request.get("validFrom")).toInstant());
        assertThat(Instant.parse(body.get("validUntil").asText()))
                .isEqualTo(OffsetDateTime.parse((String) request.get("validUntil")).toInstant());
    }

    @Test
    @DisplayName("R2.1 maxDiscountAmount 생략 → null(제한 없음)")
    void create_withoutMaxDiscount_returnsNull() {
        JsonNode body = api.post("/api/coupons", coupon(newCouponCode(), "FIXED", 1000)).assertStatus(201).body();
        assertThat(body.get("maxDiscountAmount").isNull()).isTrue();
    }

    static Stream<Arguments> invalidCoupons() {
        return Stream.of(
                Arguments.of("code 소문자", mutate(c -> c.put("code", "abcd1234"))),
                Arguments.of("code 3자", mutate(c -> c.put("code", "ABC"))),
                Arguments.of("code 21자", mutate(c -> c.put("code", "A".repeat(21)))),
                Arguments.of("code 특수문자", mutate(c -> c.put("code", "ABCD-123"))),
                Arguments.of("code 누락", mutate(c -> c.remove("code"))),
                Arguments.of("type 정의 외 값", mutate(c -> c.put("type", "PERCENT"))),
                Arguments.of("FIXED value 0", mutate(c -> c.put("value", 0))),
                Arguments.of("RATE value 0", mutate(c -> { c.put("type", "RATE"); c.put("value", 0); })),
                Arguments.of("RATE value 101", mutate(c -> { c.put("type", "RATE"); c.put("value", 101); })),
                Arguments.of("minOrderAmount -1", mutate(c -> c.put("minOrderAmount", -1))),
                Arguments.of("maxDiscountAmount 0", mutate(c -> c.put("maxDiscountAmount", 0))),
                Arguments.of("totalQuantity 0", mutate(c -> c.put("totalQuantity", 0))),
                Arguments.of("validFrom == validUntil", mutate(c -> c.put("validUntil", c.get("validFrom")))),
                Arguments.of("validFrom > validUntil", mutate(c -> {
                    Object from = c.get("validFrom");
                    c.put("validFrom", c.get("validUntil"));
                    c.put("validUntil", from);
                })),
                Arguments.of("validFrom 오프셋 없음", mutate(c -> c.put("validFrom", "2026-01-01T00:00:00"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCoupons")
    @DisplayName("R2.2 검증 위반 → 400 VALIDATION_ERROR")
    void create_rejectsInvalid(String caseName, Map<String, Object> body) {
        api.post("/api/coupons", body).assertProblem(400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 RATE value 100은 허용")
    void create_rate100_accepted() {
        api.post("/api/coupons", coupon(newCouponCode(), "RATE", 100)).assertStatus(201);
    }

    @Test
    @DisplayName("R2.2 이미 있는 code → 409 DUPLICATE_COUPON_CODE")
    void create_duplicateCode_returns409() {
        String code = api.createCoupon("FIXED", 1000);
        api.post("/api/coupons", coupon(code, "RATE", 5)).assertProblem(409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.3 쿠폰 조회 → 200, 없으면 404 COUPON_NOT_FOUND")
    void get_returnsCouponOr404() {
        String code = api.createCoupon("FIXED", 700);
        JsonNode body = api.get("/api/coupons/" + code).assertStatus(200).body();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).isEqualTo("FIXED");
        assertThat(body.get("value").asLong()).isEqualTo(700);
        assertThat(body.get("usedCount").asInt()).isZero();

        api.get("/api/coupons/NOPE0000").assertProblem(404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.4 RATE 할인은 floor(subtotal × value / 100), totalPrice = subtotal − discount")
    void discount_rate_floors() {
        long productId = api.createProduct(3_333, 100);
        String code = api.createCoupon("RATE", 15);

        JsonNode order = api.createOrder(newUserId(), code, List.of(item(productId, 3))).assertStatus(201).body();

        assertThat(order.get("subtotal").asLong()).isEqualTo(9_999);
        assertThat(order.get("discount").asLong()).isEqualTo(1_499); // 1499.85 → 1499
        assertThat(order.get("totalPrice").asLong()).isEqualTo(8_500);
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 상한 적용 후 subtotal 상한 적용")
    void discount_caps() {
        long productId = api.createProduct(10_000, 100);
        Map<String, Object> capped = coupon(newCouponCode(), "RATE", 50);
        capped.put("maxDiscountAmount", 3_000);
        String rateCode = api.createCoupon(capped);
        String fixedCode = api.createCoupon("FIXED", 50_000);

        JsonNode rateOrder = api.createOrder(newUserId(), rateCode, List.of(item(productId, 1))).assertStatus(201).body();
        assertThat(rateOrder.get("discount").asLong()).isEqualTo(3_000);
        assertThat(rateOrder.get("totalPrice").asLong()).isEqualTo(7_000);

        JsonNode fixedOrder = api.createOrder(newUserId(), fixedCode, List.of(item(productId, 2))).assertStatus(201).body();
        assertThat(fixedOrder.get("subtotal").asLong()).isEqualTo(20_000);
        assertThat(fixedOrder.get("discount").asLong()).isEqualTo(20_000);
        assertThat(fixedOrder.get("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.5 유효 기간 전·후 → 409 COUPON_NOT_APPLICABLE")
    void apply_outsideValidity_returns409() {
        long productId = api.createProduct(1000, 100);
        Map<String, Object> future = coupon(newCouponCode(), "FIXED", 100);
        future.put("validFrom", OffsetDateTime.now().plusHours(1).toString());
        future.put("validUntil", OffsetDateTime.now().plusHours(2).toString());
        Map<String, Object> past = coupon(newCouponCode(), "FIXED", 100);
        past.put("validFrom", OffsetDateTime.now().minusHours(2).toString());
        past.put("validUntil", OffsetDateTime.now().minusHours(1).toString());

        api.createOrder(newUserId(), api.createCoupon(future), List.of(item(productId, 1)))
                .assertProblem(409, "COUPON_NOT_APPLICABLE");
        api.createOrder(newUserId(), api.createCoupon(past), List.of(item(productId, 1)))
                .assertProblem(409, "COUPON_NOT_APPLICABLE");
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount → 409 COUPON_NOT_APPLICABLE, 같으면 허용")
    void apply_belowMinOrderAmount_returns409() {
        long productId = api.createProduct(1000, 100);
        Map<String, Object> request = coupon(newCouponCode(), "FIXED", 100);
        request.put("minOrderAmount", 2000);
        String code = api.createCoupon(request);

        api.createOrder(newUserId(), code, List.of(item(productId, 1))).assertProblem(409, "COUPON_NOT_APPLICABLE");
        api.createOrder(newUserId(), code, List.of(item(productId, 2))).assertStatus(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이 쿠폰을 사용 중인 주문이 있으면 409 COUPON_NOT_APPLICABLE")
    void apply_sameUserTwice_returns409() {
        long productId = api.createProduct(1000, 100);
        String code = api.createCoupon("FIXED", 100);
        String user = newUserId();

        long first = api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201).id();
        api.pay(first).assertStatus(200);
        api.createOrder(user, code, List.of(item(productId, 1))).assertProblem(409, "COUPON_NOT_APPLICABLE");
        api.createOrder(newUserId(), code, List.of(item(productId, 1))).assertStatus(201);
    }

    @Test
    @DisplayName("R2.5 usedCount = totalQuantity → 409 COUPON_EXHAUSTED")
    void apply_exhausted_returns409() {
        long productId = api.createProduct(1000, 100);
        Map<String, Object> request = coupon(newCouponCode(), "FIXED", 100);
        request.put("totalQuantity", 1);
        String code = api.createCoupon(request);

        api.createOrder(newUserId(), code, List.of(item(productId, 1))).assertStatus(201);
        api.createOrder(newUserId(), code, List.of(item(productId, 1))).assertProblem(409, "COUPON_EXHAUSTED");
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 취소·결제 거절·환불 시 usedCount 감소, 같은 사용자 재사용 가능")
    void usage_restoredOnTerminalStates() {
        long productId = api.createProduct(1000, 100);
        Map<String, Object> request = coupon(newCouponCode(), "FIXED", 100);
        request.put("totalQuantity", 1);
        String code = api.createCoupon(request);
        String user = newUserId();

        long cancelled = api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201).id();
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
        api.cancel(cancelled).assertStatus(200);
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();

        long declined = api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201).id();
        PG.paymentMode(Mode.DECLINE);
        api.pay(declined).assertProblem(402, "PAYMENT_DECLINED");
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
        PG.reset();

        long refunded = api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201).id();
        api.pay(refunded).assertStatus(200);
        api.cancel(refunded).assertStatus(200);
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();

        api.createOrder(user, code, List.of(item(productId, 1))).assertStatus(201);
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    private static Map<String, Object> mutate(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = coupon(newCouponCode(), "FIXED", 1000);
        change.accept(body);
        return body;
    }
}
