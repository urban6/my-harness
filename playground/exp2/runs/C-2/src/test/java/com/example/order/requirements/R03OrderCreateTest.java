package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R3. 주문 생성·조회 */
class R03OrderCreateTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ R3.1

    @Test
    @DisplayName("R3.1 주문 생성은 201 + Location, status=PENDING_PAYMENT, userId는 X-User-Id이다")
    void r3_1_create_returnsCreatedWithLocation() {
        long productId = newProduct(2_000, 10);
        String user = uniqueUser();

        ApiResponse r = postOrder(user, uniqueKey(), orderJson(null, line(productId, 2)));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).endsWith("/api/orders/" + r.id());
        assertThat(r.text("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(r.text("userId")).isEqualTo(user);
        assertThat(r.json().get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R3.1 생성 응답 본문은 R3.5 조회 본문과 같다")
    void r3_1_createBody_equalsGetBody() {
        long productId = newProduct(2_000, 10);
        String coupon = newCoupon("RATE", 10, 0, null, 5);

        ApiResponse created = placeOrder(coupon, line(productId, 2));

        assertThat(getOrder(created.id()).json()).isEqualTo(created.json());
    }

    @Test
    @DisplayName("R3.1 Location이 가리키는 URL로 주문을 조회할 수 있다")
    void r3_1_location_isResolvable() {
        ApiResponse created = placeOrderOk(newProduct(1_000, 5), 1);
        String location = created.header("Location");

        ApiResponse fetched = get(location.substring(location.indexOf("/api/")));

        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.id()).isEqualTo(created.id());
    }

    @ParameterizedTest(name = "R3.1/R3.2 허용 헤더 {0} -> 201")
    @MethodSource("validHeaders")
    @DisplayName("R3.1 헤더 경계 이내(X-User-Id 1~50자, Idempotency-Key 1~64자)는 201이다")
    void r3_1_validHeaderBoundaries_areAccepted(String label, String user, String key) {
        long productId = newProduct(1_000, 5);

        ApiResponse r = postOrder(user, key, orderJson(null, line(productId, 1)));

        assertThat(r.status()).as("%s: %s", label, r).isEqualTo(201);
        assertThat(r.text("userId")).isEqualTo(user);
    }

    static Stream<Arguments> validHeaders() {
        return Stream.of(
                arguments("X-User-Id 1자", "u", uniqueKey()),
                arguments("X-User-Id 50자", "u".repeat(50), uniqueKey()),
                arguments("Idempotency-Key 1자", uniqueUser(), "z"),
                arguments("Idempotency-Key 64자", uniqueUser(), (uniqueKey() + "x".repeat(64)).substring(0, 64)));
    }

    @ParameterizedTest(name = "R3.1/R3.2 잘못된 헤더 {0} -> 400")
    @MethodSource("invalidHeaders")
    @DisplayName("R3.2 X-User-Id·Idempotency-Key 누락/공백/길이 초과는 400이고 아무것도 예약하지 않는다")
    void r3_2_invalidHeaders_return400(String label, String user, String key) {
        long productId = newProduct(1_000, 5);

        ApiResponse r = postOrder(user, key, orderJson(null, line(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reserved(productId)).isZero();
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                arguments("X-User-Id 누락", null, "key-1"),
                arguments("X-User-Id 공백만", "   ", "key-2"),
                arguments("X-User-Id 51자", "u".repeat(51), "key-3"),
                arguments("Idempotency-Key 누락", "user-1", null),
                arguments("Idempotency-Key 공백만", "user-2", "   "),
                arguments("Idempotency-Key 65자", "user-3", "k".repeat(65)));
    }

    // ------------------------------------------------------------------ R3.2

    @Test
    @DisplayName("R3.2 items가 1개이면 201, 20개이면 201이다")
    void r3_2_itemsBoundary_1and20_accepted() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(line(newProduct(1_000, 5), 1));
        }

        assertThat(postOrder(uniqueUser(), uniqueKey(), orderJson(null, lines.get(0))).status()).isEqualTo(201);
        ApiResponse twenty = postOrder(uniqueUser(), uniqueKey(), orderJson(null, lines.toArray(new Line[0])));

        assertThat(twenty.status()).as("20 items: %s", twenty).isEqualTo(201);
        assertThat(twenty.json().get("items")).hasSize(20);
        assertThat(twenty.longValue("subtotal")).isEqualTo(20_000);
    }

    @Test
    @DisplayName("R3.2 items가 21개이면 400이고 아무것도 예약하지 않는다")
    void r3_2_items21_returns400() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            lines.add(line(newProduct(1_000, 5), 1));
        }

        ApiResponse r = postOrder(uniqueUser(), uniqueKey(), orderJson(null, lines.toArray(new Line[0])));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(lines).allSatisfy(l -> assertThat(reserved(l.productId())).isZero());
    }

    @ParameterizedTest(name = "R3.2 잘못된 본문 {0} -> 400")
    @MethodSource("invalidBodies")
    @DisplayName("R3.2 items·quantity·productId·couponCode 위반은 400 VALIDATION_ERROR이다")
    void r3_2_invalidBodies_return400(String label, String bodyTemplate) {
        long productId = newProduct(1_000, 5);
        long other = newProduct(1_000, 5);
        String body = bodyTemplate.replace("{P}", String.valueOf(productId)).replace("{Q}", String.valueOf(other));

        ApiResponse r = postOrder(uniqueUser(), uniqueKey(), body);

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reserved(productId)).isZero();
        assertThat(reserved(other)).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                arguments("items 빈 배열", "{\"items\":[]}"),
                arguments("items 누락", "{\"couponCode\":null}"),
                arguments("items null", "{\"items\":null}"),
                arguments("items 원소 null", "{\"items\":[null]}"),
                arguments("quantity 0", "{\"items\":[{\"productId\":{P},\"quantity\":0}]}"),
                arguments("quantity -1", "{\"items\":[{\"productId\":{P},\"quantity\":-1}]}"),
                arguments("quantity 1001", "{\"items\":[{\"productId\":{P},\"quantity\":1001}]}"),
                arguments("quantity 누락", "{\"items\":[{\"productId\":{P}}]}"),
                arguments("quantity 소수", "{\"items\":[{\"productId\":{P},\"quantity\":1.5}]}"),
                arguments("quantity 문자열", "{\"items\":[{\"productId\":{P},\"quantity\":\"1\"}]}"),
                arguments("productId 누락", "{\"items\":[{\"quantity\":1}]}"),
                arguments("productId 문자열", "{\"items\":[{\"productId\":\"abc\",\"quantity\":1}]}"),
                arguments("productId 중복", "{\"items\":[{\"productId\":{P},\"quantity\":1},{\"productId\":{P},\"quantity\":2}]}"),
                arguments("productId 중복(세 번째 항목)",
                        "{\"items\":[{\"productId\":{P},\"quantity\":1},{\"productId\":{Q},\"quantity\":1},{\"productId\":{P},\"quantity\":1}]}"),
                arguments("items가 객체", "{\"items\":{}}"),
                arguments("couponCode가 숫자", "{\"items\":[{\"productId\":{P},\"quantity\":1}],\"couponCode\":123}"));
    }

    @Test
    @DisplayName("R3.2 quantity 1과 1000은 허용되고, couponCode는 생략해도 null이어도 된다")
    void r3_2_quantityBoundary_andOptionalCoupon() {
        long p1 = newProduct(1_000, 1_000);
        long p2 = newProduct(1_000, 1_000);

        ApiResponse omitted = postOrder(uniqueUser(), uniqueKey(),
                "{\"items\":[{\"productId\":" + p1 + ",\"quantity\":1}]}");
        ApiResponse max = postOrder(uniqueUser(), uniqueKey(), orderJson(null, line(p2, 1_000)));

        assertThat(omitted.status()).isEqualTo(201);
        assertThat(omitted.json().get("couponCode").isNull()).isTrue();
        assertThat(max.status()).isEqualTo(201);
        assertThat(max.json().get("items").get(0).get("quantity").asInt()).isEqualTo(1_000);
        assertThat(max.json().get("couponCode").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ R3.3

    @Test
    @DisplayName("R3.3 없는 상품이 하나라도 있으면 404 PRODUCT_NOT_FOUND이고 다른 상품도 예약되지 않는다")
    void r3_3_unknownProduct_returns404_andReservesNothing() {
        long productId = newProduct(1_000, 5);

        ApiResponse r = placeOrder(null, line(productId, 1), line(999_999_999L, 1));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 없는 쿠폰은 404 COUPON_NOT_FOUND이고 상품이 예약되지 않는다")
    void r3_3_unknownCoupon_returns404_andReservesNothing() {
        long productId = newProduct(1_000, 5);

        ApiResponse r = placeOrder("NOSUCH99", line(productId, 1));

        assertProblem(r, 404, "COUPON_NOT_FOUND");
        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 available이 부족한 항목이 하나라도 있으면 409 INSUFFICIENT_STOCK이다 (두 번째 항목이 부족한 경우)")
    void r3_3_anyItemInsufficient_returns409() {
        long enough = newProduct(1_000, 10);
        long scarce = newProduct(1_000, 2);

        ApiResponse r = placeOrder(null, line(enough, 5), line(scarce, 3));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R3.3 quantity == available이면 성공하고 available+1이면 409이다")
    void r3_3_stockBoundary_equalSucceeds_oneMoreFails() {
        long productId = newProduct(1_000, 5);

        assertThat(placeOrder(null, line(productId, 6)).code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(placeOrder(null, line(productId, 5)).status()).isEqualTo(201);
        assertThat(available(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 이미 예약된 수량은 available에서 빠진다 (stock 10, reserved 7이면 4개 주문은 409)")
    void r3_3_availableConsidersReserved() {
        long productId = newProduct(1_000, 10);
        placeOrderOk(productId, 7);

        assertThat(placeOrder(null, line(productId, 4)).code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(placeOrder(null, line(productId, 3)).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ R3.4

    @Test
    @DisplayName("R3.4 생성하면 각 상품의 reserved가 주문 수량만큼, 쿠폰 usedCount가 1 늘어난다")
    void r3_4_create_incrementsReservedAndUsedCount() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        ApiResponse r = placeOrder(coupon, line(p1, 3), line(p2, 5));

        assertThat(r.status()).isEqualTo(201);
        assertThat(reserved(p1)).isEqualTo(3);
        assertThat(reserved(p2)).isEqualTo(5);
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 재고가 부족하면 쿠폰 usedCount도 늘지 않고 앞 항목도 예약되지 않는다 (전부 아니면 전무)")
    void r3_4_insufficientStock_appliesNothing() {
        long enough = newProduct(1_000, 10);
        long scarce = newProduct(1_000, 1);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        ApiResponse r = placeOrder(coupon, line(enough, 4), line(scarce, 2));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(reserved(enough)).isZero();
        assertThat(reserved(scarce)).isZero();
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 적용 불가(minOrderAmount 미달)이면 모든 상품의 예약이 반영되지 않는다")
    void r3_4_couponNotApplicable_appliesNothing() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 1_000_000, null, 5);

        ApiResponse r = placeOrder(coupon, line(p1, 2), line(p2, 2));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(reserved(p1)).isZero();
        assertThat(reserved(p2)).isZero();
        assertThat(usedCount(coupon)).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 소진되었으면 모든 상품의 예약이 반영되지 않는다")
    void r3_4_couponExhausted_appliesNothing() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 1);
        assertThat(placeOrder(coupon, line(p1, 1)).status()).isEqualTo(201);

        ApiResponse r = placeOrder(coupon, line(p1, 2), line(p2, 2));

        assertProblem(r, 409, "COUPON_EXHAUSTED");
        assertThat(reserved(p1)).isEqualTo(1);
        assertThat(reserved(p2)).isZero();
        assertThat(usedCount(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 상품은 있고 쿠폰이 없으면(404) 상품 예약이 반영되지 않는다")
    void r3_4_unknownCoupon_appliesNothing() {
        long productId = newProduct(1_000, 10);

        assertThat(placeOrder("NOSUCH88", line(productId, 3)).status()).isEqualTo(404);

        assertThat(reserved(productId)).isZero();
    }

    @Test
    @DisplayName("R3.4 400 검증 실패는 아무것도 반영하지 않는다")
    void r3_4_validationFailure_appliesNothing() {
        long productId = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, 0, null, 5);

        ApiResponse r = postOrder(uniqueUser(), uniqueKey(), orderJson(coupon, line(productId, 1), line(productId, 2)));

        assertThat(r.status()).isEqualTo(400);
        assertThat(reserved(productId)).isZero();
        assertThat(usedCount(coupon)).isZero();
    }

    // ------------------------------------------------------------------ R3.5

    @Test
    @DisplayName("R3.5 주문 조회는 200이고 {id,userId,status,items,couponCode,subtotal,discount,totalPrice,createdAt,expiresAt,paidAt}이다")
    void r3_5_get_returnsAllFields() {
        long p1 = newProduct(1_500, 10);
        long p2 = newProduct(700, 10);
        String coupon = newCoupon("FIXED", 500, 0, null, 5);
        String user = uniqueUser();
        ApiResponse created = postOrder(user, uniqueKey(), orderJson(coupon, line(p2, 3), line(p1, 2)));

        ApiResponse r = getOrder(created.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.text("userId")).isEqualTo(user);
        assertThat(r.text("couponCode")).isEqualTo(coupon);
        assertThat(r.longValue("subtotal")).isEqualTo(700 * 3 + 1_500 * 2);
        assertThat(r.longValue("discount")).isEqualTo(500);
        assertThat(r.longValue("totalPrice")).isEqualTo(700 * 3 + 1_500 * 2 - 500);
    }

    @Test
    @DisplayName("R3.5 items는 요청 순서를 유지하고 각 원소는 {productId,quantity,unitPrice}이다")
    void r3_5_items_keepRequestOrder_andShape() {
        long p1 = newProduct(1_500, 10);
        long p2 = newProduct(700, 10);
        long p3 = newProduct(90, 10);

        ApiResponse r = getOrder(placeOrder(null, line(p3, 1), line(p1, 2), line(p2, 3)).id());

        JsonNode items = r.json().get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(p3);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(90);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(p1);
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(2).get("productId").asLong()).isEqualTo(p2);
        assertThat(items.get(2).get("unitPrice").asLong()).isEqualTo(700);
    }

    @Test
    @DisplayName("R3.5 쿠폰을 쓰지 않은 주문은 couponCode=null, discount=0이고 paidAt은 null이다 (필드는 생략되지 않는다)")
    void r3_5_noCoupon_nullFieldsArePresent() {
        ApiResponse r = getOrder(placeOrderOk(newProduct(1_000, 5), 1).id());

        assertThat(r.json().has("couponCode")).isTrue();
        assertThat(r.json().get("couponCode").isNull()).isTrue();
        assertThat(r.json().has("paidAt")).isTrue();
        assertThat(r.json().get("paidAt").isNull()).isTrue();
        assertThat(r.longValue("discount")).isZero();
    }

    @Test
    @DisplayName("R3.5 C2 createdAt·expiresAt은 오프셋이 포함된 ISO-8601이고 expiresAt = createdAt + 15분(기본 TTL)이다")
    void r3_5_timestamps_areIsoWithOffset_andExpiresAtIsCreatedAtPlusTtl() {
        Instant before = Instant.now();
        ApiResponse r = getOrder(placeOrderOk(newProduct(1_000, 5), 1).id());
        Instant after = Instant.now();

        assertThat(r.text("createdAt")).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(r.text("expiresAt")).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(Duration.between(instant(r, "createdAt"), instant(r, "expiresAt"))).isEqualTo(Duration.ofMinutes(15));
        assertThat(instant(r, "createdAt")).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    @DisplayName("R3.5 unitPrice는 주문 시점 상품 가격이다")
    void r3_5_unitPrice_isPriceAtOrderTime() {
        long productId = newProduct(4_321, 5);

        ApiResponse r = getOrder(placeOrderOk(productId, 2).id());

        assertThat(r.json().get("items").get(0).get("unitPrice").asLong()).isEqualTo(getProduct(productId).longValue("price"));
        assertThat(r.longValue("subtotal")).isEqualTo(4_321 * 2);
    }

    @Test
    @DisplayName("R3.5 없는 주문 조회는 404 ORDER_NOT_FOUND이다")
    void r3_5_get_unknown_returns404() {
        assertProblem(getOrder(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.5 숫자가 아닌 주문 id는 400이다")
    void r3_5_get_nonNumericId_returns400() {
        assertProblem(get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }
}
