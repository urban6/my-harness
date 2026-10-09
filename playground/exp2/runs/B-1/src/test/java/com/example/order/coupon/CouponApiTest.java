package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R2 쿠폰 등록·조회·적용 조건·사용 복원")
class CouponApiTest extends IntegrationTest {

    @Test
    @DisplayName("R2.1 등록하면 201 + Location, usedCount 0, 생략한 minOrderAmount 는 0")
    void create_returns201() {
        String code = uniqueCouponCode();
        Map<String, Object> body = new HashMap<>(couponBody(code, Map.of(
                "type", "RATE", "value", 10, "maxDiscountAmount", 5_000, "totalQuantity", 7,
                "validFrom", "2026-01-01T00:00:00+09:00", "validUntil", "2027-01-01T00:00:00+09:00")));
        body.remove("minOrderAmount");

        ApiResponse res = api.post("/api/coupons", body);

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.header("Location")).endsWith("/api/coupons/" + code);
        JsonNode c = res.body();
        assertThat(c.get("code").asText()).isEqualTo(code);
        assertThat(c.get("type").asText()).isEqualTo("RATE");
        assertThat(c.get("value").asLong()).isEqualTo(10);
        assertThat(c.get("minOrderAmount").asLong()).isZero();
        assertThat(c.get("maxDiscountAmount").asLong()).isEqualTo(5_000);
        assertThat(c.get("totalQuantity").asInt()).isEqualTo(7);
        assertThat(c.get("usedCount").asInt()).isZero();
        // C2: 오프셋 포함 ISO-8601, 같은 시각
        assertThat(OffsetDateTime.parse(c.get("validFrom").asText()))
                .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+09:00").withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("R2.3 조회 형태와 생략한 maxDiscountAmount 는 null(제한 없음)")
    void get_returnsCoupon() {
        String code = createCoupon(Map.of());

        ApiResponse res = api.get("/api/coupons/" + code);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "code", "type", "value", "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount",
                "validFrom", "validUntil");
        assertThat(res.body().get("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void get_returns404WhenMissing() {
        assertProblem(api.get("/api/coupons/NOPE0000"), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.2 이미 있는 code 는 409 DUPLICATE_COUPON_CODE")
    void create_rejectsDuplicateCode() {
        String code = createCoupon(Map.of());
        assertProblem(api.post("/api/coupons", couponBody(code, Map.of())), 409, "DUPLICATE_COUPON_CODE");
    }

    static Stream<Arguments> invalidCoupons() {
        return Stream.of(
                Arguments.of("code 소문자", Map.of("code", "abcd1234")),
                Arguments.of("code 3자", Map.of("code", "AB1")),
                Arguments.of("code 21자", Map.of("code", "A".repeat(21))),
                Arguments.of("code 특수문자", Map.of("code", "ABC-123")),
                Arguments.of("type 미정의", Map.of("type", "PERCENT")),
                Arguments.of("FIXED value 0", Map.of("type", "FIXED", "value", 0)),
                Arguments.of("RATE value 0", Map.of("type", "RATE", "value", 0)),
                Arguments.of("RATE value 101", Map.of("type", "RATE", "value", 101)),
                Arguments.of("minOrderAmount 음수", Map.of("minOrderAmount", -1)),
                Arguments.of("maxDiscountAmount 0", Map.of("maxDiscountAmount", 0)),
                Arguments.of("totalQuantity 0", Map.of("totalQuantity", 0)),
                Arguments.of("validFrom = validUntil",
                        Map.of("validFrom", "2026-01-01T00:00:00Z", "validUntil", "2026-01-01T00:00:00Z")),
                Arguments.of("validFrom > validUntil",
                        Map.of("validFrom", "2026-02-01T00:00:00Z", "validUntil", "2026-01-01T00:00:00Z")),
                Arguments.of("오프셋 없는 시각", Map.of("validFrom", "2026-01-01T00:00:00")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCoupons")
    @DisplayName("R2.2 규칙 위반은 400")
    void create_rejectsInvalid(String name, Map<String, Object> overrides) {
        Map<String, Object> body = couponBody(uniqueCouponCode(), Map.of());
        body.putAll(overrides);
        assertProblem(api.post("/api/coupons", body), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 필수 필드(code·type·value·totalQuantity·validFrom·validUntil) 누락은 400")
    void create_rejectsMissingRequired() {
        for (String field : List.of("code", "type", "value", "totalQuantity", "validFrom", "validUntil")) {
            Map<String, Object> body = couponBody(uniqueCouponCode(), Map.of());
            body.remove(field);
            assertProblem(api.post("/api/coupons", body), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R2.2 경계값(code 4자·20자, RATE 1·100)은 허용")
    void create_acceptsBoundaries() {
        assertThat(api.post("/api/coupons", couponBody(uniqueCouponCode().substring(0, 4), Map.of())).status())
                .isEqualTo(201);
        assertThat(api.post("/api/coupons",
                couponBody((uniqueCouponCode() + "XXXXX").substring(0, 20), Map.of())).status()).isEqualTo(201);
        assertThat(api.post("/api/coupons",
                couponBody(uniqueCouponCode(), Map.of("type", "RATE", "value", 1))).status()).isEqualTo(201);
        assertThat(api.post("/api/coupons",
                couponBody(uniqueCouponCode(), Map.of("type", "RATE", "value", 100))).status()).isEqualTo(201);
    }

    // ---- R2.5 적용 조건 ----------------------------------------------------

    @Test
    @DisplayName("R2.5 유효 기간 시작 전이면 409 COUPON_NOT_APPLICABLE")
    void notApplicable_beforeValidFrom() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String code = createCoupon(Map.of(
                "validFrom", now.plusHours(1).toString(), "validUntil", now.plusHours(2).toString()));
        long productId = createProduct(10_000, 10);

        assertProblem(placeOrder(uniqueUser(), code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
        assertProduct(productId, 10, 0);
        assertThat(usedCount(code)).isZero();
    }

    @Test
    @DisplayName("R2.5 유효 기간이 끝났으면(validUntil 이후) 409 COUPON_NOT_APPLICABLE")
    void notApplicable_afterValidUntil() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String code = createCoupon(Map.of(
                "validFrom", now.minusHours(2).toString(), "validUntil", now.minusSeconds(1).toString()));
        long productId = createProduct(10_000, 10);

        assertProblem(placeOrder(uniqueUser(), code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount 면 409, 같으면 사용 가능")
    void minOrderAmount() {
        String code = createCoupon(Map.of("minOrderAmount", 20_000));
        long productId = createProduct(10_000, 10);

        assertProblem(placeOrder(uniqueUser(), code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
        assertThat(placeOrder(uniqueUser(), code, List.of(item(productId, 2))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이 쿠폰을 사용 중인 주문이 있으면 409, 다른 사용자는 가능")
    void sameUserCannotUseTwice() {
        String code = createCoupon(Map.of());
        long productId = createProduct(10_000, 10);
        String user = uniqueUser();

        createOrder(user, code, List.of(item(productId, 1)));

        assertProblem(placeOrder(user, code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
        assertThat(placeOrder(uniqueUser(), code, List.of(item(productId, 1))).status()).isEqualTo(201);
        assertThat(usedCount(code)).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.5 usedCount = totalQuantity 면 409 COUPON_EXHAUSTED, 예약은 반영되지 않음")
    void exhausted() {
        String code = createCoupon(Map.of("totalQuantity", 1));
        long productId = createProduct(10_000, 10);
        createOrder(uniqueUser(), code, List.of(item(productId, 1)));

        assertProblem(placeOrder(uniqueUser(), code, List.of(item(productId, 2))), 409, "COUPON_EXHAUSTED");
        assertProduct(productId, 10, 1);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    // ---- R2.6 사용 복원 ----------------------------------------------------

    @Test
    @DisplayName("R2.6 취소(CANCELLED)되면 usedCount 가 줄고 같은 사용자가 다시 쓸 수 있다")
    void restoredOnCancel() {
        String code = createCoupon(Map.of("totalQuantity", 1));
        long productId = createProduct(10_000, 10);
        String user = uniqueUser();
        long orderId = createOrder(user, code, List.of(item(productId, 1)));
        assertThat(usedCount(code)).isEqualTo(1);

        assertThat(action(orderId, "cancel").status()).isEqualTo(200);

        assertThat(usedCount(code)).isZero();
        assertThat(placeOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 결제 거절(PAYMENT_FAILED)되면 사용이 복원된다")
    void restoredOnPaymentFailure() {
        String code = createCoupon(Map.of());
        long productId = createProduct(10_000, 10);
        String user = uniqueUser();
        long orderId = createOrder(user, code, List.of(item(productId, 1)));
        PG.paymentMode(com.example.order.support.FakePaymentGateway.Mode.DECLINE);

        assertThat(pay(orderId).status()).isEqualTo(402);

        assertThat(usedCount(code)).isZero();
        assertThat(placeOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 환불(REFUNDED)되면 사용이 복원된다")
    void restoredOnRefund() {
        String code = createCoupon(Map.of());
        long productId = createProduct(10_000, 10);
        String user = uniqueUser();
        long orderId = createOrder(user, code, List.of(item(productId, 1)));
        payOk(orderId);
        assertThat(usedCount(code)).isEqualTo(1);

        assertThat(action(orderId, "cancel").status()).isEqualTo(200);

        assertThat(usedCount(code)).isZero();
        assertThat(placeOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 결제 완료·배송된 주문은 계속 사용 중으로 센다")
    void paidOrderKeepsUsage() {
        String code = createCoupon(Map.of());
        long productId = createProduct(10_000, 10);
        String user = uniqueUser();
        long orderId = createOrder(user, code, List.of(item(productId, 1)));
        payOk(orderId);
        action(orderId, "ship");

        assertThat(usedCount(code)).isEqualTo(1);
        assertProblem(placeOrder(user, code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
    }
}
