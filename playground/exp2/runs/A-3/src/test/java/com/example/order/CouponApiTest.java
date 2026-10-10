package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R2. 쿠폰")
class CouponApiTest extends IntegrationTest {

    @Test
    @DisplayName("R2.1 등록하면 201, Location, usedCount=0. R2.3 조회 형태 동일")
    void createAndGetCoupon() {
        String code = uniqueCouponCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", "RATE");
        body.put("value", 15);
        body.put("minOrderAmount", 20_000);
        body.put("maxDiscountAmount", 5_000);
        body.put("totalQuantity", 3);
        body.put("validFrom", "2026-01-01T00:00:00+09:00");
        body.put("validUntil", "2027-01-01T00:00:00+09:00");

        Resp created = post("/api/coupons", body);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).hasValue("/api/coupons/" + code);
        Resp fetched = get("/api/coupons/" + code);
        assertThat(fetched.status()).isEqualTo(200);
        for (JsonNode json : List.of(created.json(), fetched.json())) {
            assertThat(json.path("code").asText()).isEqualTo(code);
            assertThat(json.path("type").asText()).isEqualTo("RATE");
            assertThat(json.path("value").asLong()).isEqualTo(15);
            assertThat(json.path("minOrderAmount").asLong()).isEqualTo(20_000);
            assertThat(json.path("maxDiscountAmount").asLong()).isEqualTo(5_000);
            assertThat(json.path("totalQuantity").asInt()).isEqualTo(3);
            assertThat(json.path("usedCount").asInt()).isZero();
            assertThat(OffsetDateTime.parse(json.path("validFrom").asText()))
                    .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+09:00"));
            assertThat(OffsetDateTime.parse(json.path("validUntil").asText()))
                    .isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00+09:00"));
        }
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 생략 시 0, maxDiscountAmount 생략 시 null(제한 없음)")
    void optionalFieldsDefault() {
        Resp r = post("/api/coupons", couponBody(uniqueCouponCode(), "FIXED", 1_000));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().path("minOrderAmount").asLong()).isZero();
        assertThat(r.json().has("maxDiscountAmount")).isTrue();
        assertThat(r.json().path("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.2 이미 있는 code 는 409 DUPLICATE_COUPON_CODE")
    void duplicateCode() {
        String code = createCoupon("FIXED", 1_000);
        assertProblem(post("/api/coupons", couponBody(code, "RATE", 10)), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void couponNotFound() {
        assertProblem(get("/api/coupons/NOSUCHCOUPON1"), 404, "COUPON_NOT_FOUND");
    }

    record Invalid(String name, Consumer<Map<String, Object>> mutate) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Invalid> invalidCoupons() {
        return Stream.of(
                new Invalid("code 누락", b -> b.remove("code")),
                new Invalid("code 소문자", b -> b.put("code", "abcd1234")),
                new Invalid("code 3자", b -> b.put("code", "AB1")),
                new Invalid("code 21자", b -> b.put("code", "A".repeat(21))),
                new Invalid("code 특수문자", b -> b.put("code", "ABCD-123")),
                new Invalid("type 누락", b -> b.remove("type")),
                new Invalid("type 미정의", b -> b.put("type", "PERCENT")),
                new Invalid("value 누락", b -> b.remove("value")),
                new Invalid("FIXED value 0", b -> b.put("value", 0)),
                new Invalid("RATE value 0", b -> {
                    b.put("type", "RATE");
                    b.put("value", 0);
                }),
                new Invalid("RATE value 101", b -> {
                    b.put("type", "RATE");
                    b.put("value", 101);
                }),
                new Invalid("minOrderAmount 음수", b -> b.put("minOrderAmount", -1)),
                new Invalid("maxDiscountAmount 0", b -> b.put("maxDiscountAmount", 0)),
                new Invalid("totalQuantity 누락", b -> b.remove("totalQuantity")),
                new Invalid("totalQuantity 0", b -> b.put("totalQuantity", 0)),
                new Invalid("validFrom 누락", b -> b.remove("validFrom")),
                new Invalid("validUntil 오프셋 없음", b -> b.put("validUntil", "2030-01-01T00:00:00")),
                new Invalid("validFrom == validUntil", b -> b.put("validUntil", b.get("validFrom"))),
                new Invalid("validFrom > validUntil", b -> {
                    b.put("validFrom", "2030-01-02T00:00:00Z");
                    b.put("validUntil", "2030-01-01T00:00:00Z");
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCoupons")
    @DisplayName("R2.2 검증 위반은 400 VALIDATION_ERROR")
    void invalidCoupon(Invalid invalid) {
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 1_000);
        invalid.mutate().accept(body);
        assertProblem(post("/api/coupons", body), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 경계값(code 4자/20자, RATE 1·100, minOrderAmount 0, maxDiscountAmount 1) 허용")
    void boundariesAccepted() {
        String four = uniqueCouponCode().substring(0, 4);
        Map<String, Object> rate100 = couponBody(uniqueCouponCode().substring(0, 12) + "ABCDEFGH", "RATE", 100);
        rate100.put("minOrderAmount", 0);
        rate100.put("maxDiscountAmount", 1);
        assertThat(post("/api/coupons", rate100).status()).isEqualTo(201);
        assertThat(post("/api/coupons", couponBody(uniqueCouponCode(), "RATE", 1)).status()).isEqualTo(201);
        Resp shortCode = post("/api/coupons", couponBody(four, "FIXED", 1));
        assertThat(shortCode.status()).isIn(201, 409); // 4자 무작위 코드는 드물게 겹칠 수 있다
    }

    @Nested
    @DisplayName("R2.4 할인액 계산")
    class Discount {

        @Test
        @DisplayName("FIXED 는 value 만큼 할인")
        void fixed() {
            long productId = createProduct(10_000, 100);
            JsonNode order = placeOrder(uniqueUser(), createCoupon("FIXED", 3_000), item(productId, 2));
            assertAmounts(order, 20_000, 3_000, 17_000);
        }

        @Test
        @DisplayName("RATE 는 floor(subtotal × value / 100)")
        void rateIsFloored() {
            long productId = createProduct(999, 100);
            JsonNode order = placeOrder(uniqueUser(), createCoupon("RATE", 15), item(productId, 1));
            // 999 * 15 / 100 = 149.85 → 149
            assertAmounts(order, 999, 149, 850);
        }

        @Test
        @DisplayName("maxDiscountAmount 로 상한")
        void cappedByMaxDiscount() {
            long productId = createProduct(100_000, 100);
            Map<String, Object> body = couponBody(uniqueCouponCode(), "RATE", 50);
            body.put("maxDiscountAmount", 7_000);
            JsonNode order = placeOrder(uniqueUser(), createCoupon(body), item(productId, 1));
            assertAmounts(order, 100_000, 7_000, 93_000);
        }

        @Test
        @DisplayName("마지막으로 subtotal 로 상한 (FIXED 가 subtotal 보다 크면 0원)")
        void cappedBySubtotal() {
            long productId = createProduct(1_000, 100);
            JsonNode order = placeOrder(uniqueUser(), createCoupon("FIXED", 5_000), item(productId, 2));
            assertAmounts(order, 2_000, 2_000, 0);
        }

        @Test
        @DisplayName("C1. int 범위를 넘는 금액도 정확히 계산")
        void largeAmounts() {
            long p1 = createProduct(10_000_000, 1_000);
            long p2 = createProduct(9_999_999, 1_000);
            JsonNode order = placeOrder(uniqueUser(), createCoupon("RATE", 33), item(p1, 1_000), item(p2, 1_000));
            long subtotal = 10_000_000L * 1_000 + 9_999_999L * 1_000; // 19,999,999,000
            long discount = subtotal * 33 / 100;
            assertAmounts(order, subtotal, discount, subtotal - discount);
        }

        private void assertAmounts(JsonNode order, long subtotal, long discount, long total) {
            assertThat(order.path("subtotal").asLong()).isEqualTo(subtotal);
            assertThat(order.path("discount").asLong()).isEqualTo(discount);
            assertThat(order.path("totalPrice").asLong()).isEqualTo(total);
        }
    }

    @Nested
    @DisplayName("R2.5 쿠폰 적용 조건")
    class Applicability {

        @Test
        @DisplayName("validFrom 이전이면 409 COUPON_NOT_APPLICABLE")
        void notYetValid() {
            Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
            body.put("validFrom", OffsetDateTime.now().plusHours(1).toString());
            body.put("validUntil", OffsetDateTime.now().plusHours(2).toString());
            assertNotApplicable(createCoupon(body));
        }

        @Test
        @DisplayName("validUntil 이후면 409 COUPON_NOT_APPLICABLE")
        void alreadyEnded() {
            Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
            body.put("validFrom", OffsetDateTime.now().minusHours(2).toString());
            body.put("validUntil", OffsetDateTime.now().minusHours(1).toString());
            assertNotApplicable(createCoupon(body));
        }

        @Test
        @DisplayName("subtotal < minOrderAmount 이면 409, 같으면 적용")
        void belowMinimum() {
            Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
            body.put("minOrderAmount", 2_000);
            String code = createCoupon(body);
            long productId = createProduct(1_000, 10);

            Resp below = createOrder(uniqueUser(), code, List.of(item(productId, 1)));
            assertProblem(below, 409, "COUPON_NOT_APPLICABLE");
            Resp exact = createOrder(uniqueUser(), code, List.of(item(productId, 2)));
            assertThat(exact.status()).isEqualTo(201);
        }

        @Test
        @DisplayName("같은 사용자가 이 쿠폰을 사용 중인 주문이 있으면 409, 다른 사용자는 가능")
        void sameUserAlreadyUsing() {
            String code = createCoupon("FIXED", 100);
            long productId = createProduct(1_000, 10);
            String user = uniqueUser();
            placeOrder(user, code, item(productId, 1));

            assertProblem(createOrder(user, code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
            assertThat(createOrder(uniqueUser(), code, List.of(item(productId, 1))).status()).isEqualTo(201);
        }

        @Test
        @DisplayName("결제·배송 완료된 주문도 사용 중으로 본다")
        void deliveredStillUsing() {
            String code = createCoupon("FIXED", 100);
            long productId = createProduct(1_000, 10);
            String user = uniqueUser();
            long orderId = placeOrder(user, code, item(productId, 1)).path("id").asLong();
            assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);
            assertThat(post("/api/orders/" + orderId + "/ship", null).status()).isEqualTo(200);
            assertThat(post("/api/orders/" + orderId + "/deliver", null).status()).isEqualTo(200);

            assertProblem(createOrder(user, code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
        }

        @Test
        @DisplayName("usedCount = totalQuantity 면 409 COUPON_EXHAUSTED")
        void exhausted() {
            Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
            body.put("totalQuantity", 2);
            String code = createCoupon(body);
            long productId = createProduct(1_000, 10);
            placeOrder(uniqueUser(), code, item(productId, 1));
            placeOrder(uniqueUser(), code, item(productId, 1));

            assertProblem(createOrder(uniqueUser(), code, List.of(item(productId, 1))), 409, "COUPON_EXHAUSTED");
            assertThat(coupon(code).path("usedCount").asInt()).isEqualTo(2);
        }

        private void assertNotApplicable(String code) {
            long productId = createProduct(1_000, 10);
            assertProblem(createOrder(uniqueUser(), code, List.of(item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
            assertThat(coupon(code).path("usedCount").asInt()).isZero();
        }
    }

    @Nested
    @DisplayName("R2.6 usedCount 와 사용 복원")
    class UsageRestore {

        @Test
        @DisplayName("주문 생성 시 1 증가, 취소(CANCELLED)되면 1 감소하고 같은 사용자가 다시 사용 가능")
        void restoredOnCancel() {
            String code = createCoupon("FIXED", 100);
            long productId = createProduct(1_000, 10);
            String user = uniqueUser();
            long orderId = placeOrder(user, code, item(productId, 1)).path("id").asLong();
            assertThat(coupon(code).path("usedCount").asInt()).isEqualTo(1);

            assertThat(post("/api/orders/" + orderId + "/cancel", null).status()).isEqualTo(200);

            assertThat(coupon(code).path("usedCount").asInt()).isZero();
            assertThat(createOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
            assertThat(coupon(code).path("usedCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("결제 거절(PAYMENT_FAILED)이면 복원")
        void restoredOnDecline() {
            String code = createCoupon("FIXED", 100);
            long productId = createProduct(1_000, 10);
            String user = uniqueUser();
            long orderId = placeOrder(user, code, item(productId, 1)).path("id").asLong();

            assertThat(pay(orderId, "tok_decline").status()).isEqualTo(402);

            assertThat(coupon(code).path("usedCount").asInt()).isZero();
            assertThat(createOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
        }

        @Test
        @DisplayName("환불(REFUNDED)되면 복원")
        void restoredOnRefund() {
            String code = createCoupon("FIXED", 100);
            long productId = createProduct(1_000, 10);
            String user = uniqueUser();
            long orderId = placeOrder(user, code, item(productId, 1)).path("id").asLong();
            assertThat(pay(orderId, "tok_ok").status()).isEqualTo(200);
            assertThat(coupon(code).path("usedCount").asInt()).isEqualTo(1);

            assertThat(post("/api/orders/" + orderId + "/cancel", null).status()).isEqualTo(200);

            assertThat(coupon(code).path("usedCount").asInt()).isZero();
            assertThat(createOrder(user, code, List.of(item(productId, 1))).status()).isEqualTo(201);
        }
    }
}
