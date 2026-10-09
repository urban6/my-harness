package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R3. 주문 생성·조회")
class OrderCreateApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("R3.1/R3.5 생성하면 201 + Location, 본문은 조회와 같은 형태이고 status=PENDING_PAYMENT")
    void createOrder() {
        long p1 = createProduct(12_000, 10);
        long p2 = createProduct(3_500, 10);
        String code = createCoupon("type", "FIXED", "value", 2000);
        String user = newUser();

        Instant before = Instant.now();
        ResponseEntity<JsonNode> response = createOrder(user, newKey(), orderBody(code, item(p2, 3), item(p1, 1)));
        Instant after = Instant.now();

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = response.getBody();
        long id = body.get("id").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/orders/" + id);
        assertThat(body.get("userId").asText()).isEqualTo(user);
        assertThat(body.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(p2);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(3_500);
        assertThat(body.get("items").get(1).get("productId").asLong()).isEqualTo(p1);
        assertThat(body.get("items").get(1).get("unitPrice").asLong()).isEqualTo(12_000);
        assertThat(body.get("couponCode").asText()).isEqualTo(code);
        assertThat(body.get("subtotal").asLong()).isEqualTo(22_500);
        assertThat(body.get("discount").asLong()).isEqualTo(2_000);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(20_500);
        assertThat(body.has("paidAt")).isTrue();
        assertThat(body.get("paidAt").isNull()).isTrue();

        Instant createdAt = instant(body, "createdAt");
        assertThat(createdAt).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        // 기본 ORDER_PAYMENT_TTL = PT15M
        assertThat(Duration.between(createdAt, instant(body, "expiresAt"))).isEqualTo(Duration.ofMinutes(15));

        assertThat(get("/api/orders/" + id).getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("R3.5 쿠폰이 없으면 couponCode는 null, discount 0")
    void withoutCoupon() {
        long p = createProduct(1_000, 5);
        JsonNode body = placeOrder(newUser(), null, item(p, 2));
        assertThat(body.has("couponCode")).isTrue();
        assertThat(body.get("couponCode").isNull()).isTrue();
        assertThat(body.get("discount").asLong()).isZero();
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("C1 금액은 int 범위를 넘을 수 있다")
    void amountsBeyondInt() {
        long p = createProduct(10_000_000, 1_000);
        JsonNode body = placeOrder(newUser(), null, item(p, 1_000));
        assertThat(body.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("R3.3 없는 상품은 404 PRODUCT_NOT_FOUND, 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void notFound() {
        long p = createProduct(1_000, 5);
        assertProblem(createOrder(newUser(), newKey(), orderBody(null, item(p, 1), item(999_999_999L, 1))),
                404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder(newUser(), newKey(), orderBody("NOSUCHCOUPON", item(p, 1))),
                404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.3 어떤 항목이든 available이 부족하면 409 INSUFFICIENT_STOCK")
    void insufficientStock() {
        long p = createProduct(1_000, 5);
        long q = createProduct(1_000, 2);
        placeOrder(newUser(), null, item(q, 1)); // q.available = 1
        assertProblem(createOrder(newUser(), newKey(), orderBody(null, item(p, 1), item(q, 2))),
                409, "INSUFFICIENT_STOCK");
        assertThat(createOrder(newUser(), newKey(), orderBody(null, item(p, 5), item(q, 1)))
                .getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.4 생성 시 reserved가 수량만큼, 쿠폰 usedCount가 1 늘어난다")
    void reservesStockAndCoupon() {
        long p = createProduct(1_000, 10);
        long q = createProduct(2_000, 10);
        String code = createCoupon();
        placeOrder(newUser(), code, item(p, 3), item(q, 4));
        assertThat(product(p).get("reserved").asInt()).isEqualTo(3);
        assertThat(product(p).get("available").asInt()).isEqualTo(7);
        assertThat(product(q).get("reserved").asInt()).isEqualTo(4);
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 검사에 실패하면 예약·쿠폰 사용이 전혀 반영되지 않는다")
    void allOrNothing() {
        long p = createProduct(1_000, 10);
        long q = createProduct(1_000, 1);
        String code = createCoupon();

        // 두 번째 항목의 재고 부족 → 첫 번째 항목도 예약되지 않는다
        assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 2), item(q, 5))), 409, "INSUFFICIENT_STOCK");
        // 쿠폰 조건 불충족 → 재고 예약도 되지 않는다
        String minCoupon = createCoupon("minOrderAmount", 1_000_000);
        assertProblem(createOrder(newUser(), newKey(), orderBody(minCoupon, item(p, 2))), 409, "COUPON_NOT_APPLICABLE");
        // 없는 상품이 섞여 있음 → 아무 것도 예약되지 않는다
        assertProblem(createOrder(newUser(), newKey(), orderBody(code, item(p, 2), item(999_999_998L, 1))), 404, "PRODUCT_NOT_FOUND");

        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(product(q).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
        assertThat(coupon(minCoupon).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.5 unitPrice는 주문 시점 상품 가격이다")
    void unitPriceSnapshot() {
        long p = createProduct(4_321, 10);
        JsonNode order = placeOrder(newUser(), null, item(p, 2));
        assertThat(order.get("items").get(0).get("unitPrice").asLong()).isEqualTo(4_321);
        assertThat(order.get("subtotal").asLong()).isEqualTo(8_642);
    }

    @Test
    @DisplayName("R3.5 없는 주문 조회는 404")
    void orderNotFound() {
        assertProblem(get("/api/orders/999999999"), 404, "ORDER_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidHeaders")
    @DisplayName("R3.1/R3.2 헤더 누락·위반은 400")
    void invalidHeaders(String description, String[] headers) {
        long p = createProduct(1_000, 5);
        assertProblem(post("/api/orders", orderBody(null, item(p, 1)), headers), 400, "VALIDATION_ERROR");
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("X-User-Id 누락", new String[] {"Idempotency-Key", "k-1"}),
                Arguments.of("X-User-Id 공백", new String[] {"X-User-Id", "   ", "Idempotency-Key", "k-2"}),
                Arguments.of("X-User-Id 51자", new String[] {"X-User-Id", "u".repeat(51), "Idempotency-Key", "k-3"}),
                Arguments.of("Idempotency-Key 누락", new String[] {"X-User-Id", "user"}),
                Arguments.of("Idempotency-Key 65자", new String[] {"X-User-Id", "user", "Idempotency-Key", "k".repeat(65)}));
    }

    @Test
    @DisplayName("R3.1 X-User-Id 50자, Idempotency-Key 64자는 허용")
    void headerBoundaries() {
        long p = createProduct(1_000, 5);
        ResponseEntity<JsonNode> response = createOrder("u".repeat(49) + "x", newKey() + "k".repeat(28), orderBody(null, item(p, 1)));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("R3.2 본문 규칙 위반은 400")
    void invalidBodies(String description, Object body) {
        assertProblem(createOrder(newUser(), newKey(), body), 400, "VALIDATION_ERROR");
    }

    static Stream<Arguments> invalidBodies() {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(item(i, 1));
        }
        return Stream.of(
                Arguments.of("items 누락", map("couponCode", "ABCD")),
                Arguments.of("items 빈 배열", map("items", List.of())),
                Arguments.of("items 21개", map("items", tooMany)),
                Arguments.of("quantity 0", orderBody(null, item(1, 0))),
                Arguments.of("quantity 1001", orderBody(null, item(1, 1001))),
                Arguments.of("quantity 누락", map("items", List.of(map("productId", 1)))),
                Arguments.of("productId 누락", map("items", List.of(map("quantity", 1)))),
                Arguments.of("같은 productId 중복", orderBody(null, item(1, 1), item(1, 2))),
                Arguments.of("JSON 파싱 실패", "{\"items\": ["));
    }

    @Test
    @DisplayName("R3.2 items 20개, quantity 1000은 허용")
    void bodyBoundaries() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item(createProduct(10, 1_000), i == 0 ? 1_000 : 1));
        }
        assertThat(createOrder(newUser(), newKey(), map("items", items)).getStatusCode().value()).isEqualTo(201);
    }
}
