package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R2. 쿠폰")
class CouponApiTest extends IntegrationTestSupport {

    @Nested
    @DisplayName("R2.1~R2.3 등록·조회")
    class RegisterAndGet {

        @Test
        @DisplayName("R2.1 등록하면 201 + Location, 본문은 조회와 같은 형태이고 usedCount=0")
        void create() {
            String code = newCouponCode();
            ResponseEntity<JsonNode> response = post("/api/coupons", map(
                    "code", code, "type", "RATE", "value", 15, "minOrderAmount", 20000,
                    "maxDiscountAmount", 5000, "totalQuantity", 30,
                    "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2027-01-01T00:00:00+09:00"));

            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(response.getHeaders().getLocation()).hasToString("/api/coupons/" + code);
            JsonNode body = response.getBody();
            assertThat(body.get("code").asText()).isEqualTo(code);
            assertThat(body.get("type").asText()).isEqualTo("RATE");
            assertThat(body.get("value").asLong()).isEqualTo(15);
            assertThat(body.get("minOrderAmount").asLong()).isEqualTo(20000);
            assertThat(body.get("maxDiscountAmount").asLong()).isEqualTo(5000);
            assertThat(body.get("totalQuantity").asInt()).isEqualTo(30);
            assertThat(body.get("usedCount").asInt()).isZero();
            assertThat(instant(body, "validFrom")).isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant());
            assertThat(instant(body, "validUntil")).isEqualTo(OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant());
            // C2: 오프셋이 포함된 ISO-8601
            assertThat(body.get("validFrom").asText()).matches(".*(Z|[+-]\\d{2}:\\d{2})$");

            assertThat(get("/api/coupons/" + code).getBody()).isEqualTo(body);
        }

        @Test
        @DisplayName("R2.2 minOrderAmount 생략 시 0, maxDiscountAmount 생략 시 null(제한 없음)")
        void defaults() {
            String code = newCouponCode();
            ResponseEntity<JsonNode> response = post("/api/coupons",
                    couponRequest(code, "minOrderAmount", null, "maxDiscountAmount", null));
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(response.getBody().get("minOrderAmount").asLong()).isZero();
            assertThat(response.getBody().get("maxDiscountAmount").isNull()).isTrue();
        }

        @Test
        @DisplayName("R2.2 이미 있는 code면 409")
        void duplicateCode() {
            String code = createCoupon();
            assertProblem(post("/api/coupons", couponRequest(code)), 409, "DUPLICATE_COUPON_CODE");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.example.order.CouponApiTest#invalidCoupons")
        @DisplayName("R2.2 규칙을 어기면 400")
        void invalid(String description, Object[] overrides) {
            assertProblem(post("/api/coupons", couponRequest(newCouponCode(), overrides)), 400, "VALIDATION_ERROR");
        }

        @Test
        @DisplayName("R2.2 경계값(code 4자·20자, RATE 1·100, FIXED 1, totalQuantity 1)은 허용")
        void boundaries() {
            assertThat(post("/api/coupons", couponRequest("AB12")).getStatusCode().value()).isIn(201, 409);
            String code20 = newCouponCode().substring(0, 16) + "ZZZZ";
            assertThat(post("/api/coupons", couponRequest(code20)).getStatusCode().value()).isEqualTo(201);
            assertThat(post("/api/coupons", couponRequest(newCouponCode(), "type", "RATE", "value", 1))
                    .getStatusCode().value()).isEqualTo(201);
            assertThat(post("/api/coupons", couponRequest(newCouponCode(), "type", "RATE", "value", 100))
                    .getStatusCode().value()).isEqualTo(201);
            assertThat(post("/api/coupons", couponRequest(newCouponCode(), "type", "FIXED", "value", 1, "totalQuantity", 1))
                    .getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("R2.3 없는 쿠폰 조회는 404")
        void notFound() {
            assertProblem(get("/api/coupons/NOSUCHCOUPON"), 404, "COUPON_NOT_FOUND");
        }
    }

    static Stream<Arguments> invalidCoupons() {
        String now = OffsetDateTime.now().toString();
        return Stream.of(
                Arguments.of("code 누락", new Object[] {"code", null}),
                Arguments.of("code 소문자", new Object[] {"code", "abcd1234"}),
                Arguments.of("code 3자", new Object[] {"code", "AB1"}),
                Arguments.of("code 21자", new Object[] {"code", "A".repeat(21)}),
                Arguments.of("code 특수문자", new Object[] {"code", "AB-12"}),
                Arguments.of("type 누락", new Object[] {"type", null}),
                Arguments.of("type 정의되지 않음", new Object[] {"type", "PERCENT"}),
                Arguments.of("value 누락", new Object[] {"value", null}),
                Arguments.of("FIXED value 0", new Object[] {"type", "FIXED", "value", 0}),
                Arguments.of("RATE value 0", new Object[] {"type", "RATE", "value", 0}),
                Arguments.of("RATE value 101", new Object[] {"type", "RATE", "value", 101}),
                Arguments.of("minOrderAmount -1", new Object[] {"minOrderAmount", -1}),
                Arguments.of("maxDiscountAmount 0", new Object[] {"maxDiscountAmount", 0}),
                Arguments.of("totalQuantity 0", new Object[] {"totalQuantity", 0}),
                Arguments.of("totalQuantity 누락", new Object[] {"totalQuantity", null}),
                Arguments.of("validFrom 누락", new Object[] {"validFrom", null}),
                Arguments.of("validUntil 누락", new Object[] {"validUntil", null}),
                Arguments.of("validFrom = validUntil", new Object[] {"validFrom", now, "validUntil", now}),
                Arguments.of("validFrom > validUntil", new Object[] {
                        "validFrom", "2027-01-01T00:00:00Z", "validUntil", "2026-01-01T00:00:00Z"}),
                Arguments.of("오프셋 없는 시각", new Object[] {"validFrom", "2026-01-01T00:00:00"}));
    }

    @Nested
    @DisplayName("R2.4 할인액이 주문에 반영된다")
    class Discount {

        @Test
        @DisplayName("FIXED: value만큼 할인, totalPrice = subtotal − discount")
        void fixed() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("type", "FIXED", "value", 3000);
            JsonNode order = placeOrder(newUser(), code, item(p, 2));
            assertThat(order.get("subtotal").asLong()).isEqualTo(20_000);
            assertThat(order.get("discount").asLong()).isEqualTo(3000);
            assertThat(order.get("totalPrice").asLong()).isEqualTo(17_000);
        }

        @Test
        @DisplayName("RATE: floor(subtotal × value / 100)")
        void rateFloors() {
            long p = createProduct(12_345, 10);
            String code = createCoupon("type", "RATE", "value", 10);
            JsonNode order = placeOrder(newUser(), code, item(p, 1));
            assertThat(order.get("discount").asLong()).isEqualTo(1234);
            assertThat(order.get("totalPrice").asLong()).isEqualTo(11_111);
        }

        @Test
        @DisplayName("RATE 후 maxDiscountAmount로 상한")
        void rateCappedByMax() {
            long p = createProduct(100_000, 10);
            String code = createCoupon("type", "RATE", "value", 50, "maxDiscountAmount", 7000);
            JsonNode order = placeOrder(newUser(), code, item(p, 1));
            assertThat(order.get("discount").asLong()).isEqualTo(7000);
            assertThat(order.get("totalPrice").asLong()).isEqualTo(93_000);
        }

        @Test
        @DisplayName("마지막으로 subtotal로 상한 (totalPrice는 0)")
        void cappedBySubtotal() {
            long p = createProduct(5000, 10);
            String code = createCoupon("type", "FIXED", "value", 8000);
            JsonNode order = placeOrder(newUser(), code, item(p, 1));
            assertThat(order.get("discount").asLong()).isEqualTo(5000);
            assertThat(order.get("totalPrice").asLong()).isZero();
        }
    }

    @Nested
    @DisplayName("R2.5 사용 조건")
    class Applicability {

        @Test
        @DisplayName("validFrom 이전이면 COUPON_NOT_APPLICABLE")
        void notYetValid() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("validFrom", OffsetDateTime.now().plusHours(1).toString(),
                    "validUntil", OffsetDateTime.now().plusDays(1).toString());
            assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 1))), 409, "COUPON_NOT_APPLICABLE");
        }

        @Test
        @DisplayName("validUntil 이후면 COUPON_NOT_APPLICABLE")
        void expired() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("validFrom", OffsetDateTime.now().minusDays(2).toString(),
                    "validUntil", OffsetDateTime.now().minusSeconds(1).toString());
            assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 1))), 409, "COUPON_NOT_APPLICABLE");
        }

        @Test
        @DisplayName("subtotal < minOrderAmount면 COUPON_NOT_APPLICABLE, 같으면 사용 가능")
        void minOrderAmount() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("minOrderAmount", 20_000);
            assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 1))), 409, "COUPON_NOT_APPLICABLE");
            assertThat(createOrder(newUser(), newKey(), orderBody(code, item(p, 2))).getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("같은 사용자가 이 쿠폰을 사용 중인 주문이 있으면 COUPON_NOT_APPLICABLE (결제·배송 후에도 사용 중)")
        void sameUserAlreadyUsing() {
            long p = createProduct(10_000, 10);
            String code = createCoupon();
            String user = newUser();
            long orderId = placeOrder(user, code, item(p, 1)).get("id").asLong();

            assertProblem(createOrder(user, newKey(), orderBody(code, item(p, 1))), 409, "COUPON_NOT_APPLICABLE");

            assertThat(pay(orderId, newKey(), "card-ok").getStatusCode().value()).isEqualTo(200);
            post("/api/orders/" + orderId + "/ship", null);
            post("/api/orders/" + orderId + "/deliver", null);
            assertProblem(createOrder(user, newKey(), orderBody(code, item(p, 1))), 409, "COUPON_NOT_APPLICABLE");

            // 다른 사용자는 사용할 수 있다
            assertThat(createOrder(newUser(), newKey(), orderBody(code, item(p, 1))).getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("usedCount = totalQuantity면 COUPON_EXHAUSTED")
        void exhausted() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("totalQuantity", 2);
            placeOrder(newUser(), code, item(p, 1));
            placeOrder(newUser(), code, item(p, 1));
            assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(2);

            assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 1))), 409, "COUPON_EXHAUSTED");
        }
    }

    @Nested
    @DisplayName("R2.6 usedCount와 사용 복원")
    class Restore {

        @Test
        @DisplayName("주문 생성 시 1 증가, 취소되면 1 감소하고 같은 사용자가 다시 사용할 수 있다")
        void restoredOnCancel() {
            long p = createProduct(10_000, 10);
            String code = createCoupon("totalQuantity", 1);
            String user = newUser();

            long orderId = placeOrder(user, code, item(p, 1)).get("id").asLong();
            assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);

            post("/api/orders/" + orderId + "/cancel", null);
            assertThat(coupon(code).get("usedCount").asInt()).isZero();

            assertThat(createOrder(user, newKey(), orderBody(code, item(p, 1))).getStatusCode().value()).isEqualTo(201);
            assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("결제 거절(PAYMENT_FAILED)·환불(REFUNDED)도 사용이 복원된다")
        void restoredOnDeclineAndRefund() {
            long p = createProduct(10_000, 10);
            String code = createCoupon();
            String user = newUser();

            long declined = placeOrder(user, code, item(p, 1)).get("id").asLong();
            assertThat(pay(declined, newKey(), "decline-card").getStatusCode().value()).isEqualTo(402);
            assertThat(coupon(code).get("usedCount").asInt()).isZero();

            long refunded = paidOrder(user, code, item(p, 1)).get("id").asLong();
            assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
            assertThat(post("/api/orders/" + refunded + "/cancel", null).getBody().get("status").asText()).isEqualTo("REFUNDED");
            assertThat(coupon(code).get("usedCount").asInt()).isZero();

            assertThat(createOrder(user, newKey(), orderBody(code, item(p, 1))).getStatusCode().value()).isEqualTo(201);
        }
    }
}
