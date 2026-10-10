package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R3. 주문 생성·조회")
class OrderApiTest extends IntegrationTest {

    @Test
    @DisplayName("R3.1 생성하면 201, Location, R3.5 형태 본문(status=PENDING_PAYMENT)")
    void createOrder() {
        long p1 = createProduct(12_000, 10);
        long p2 = createProduct(3_500, 10);
        String coupon = createCoupon("FIXED", 1_000);
        String user = uniqueUser();

        Resp r = createOrder(user, coupon, List.of(item(p1, 2), item(p2, 3)));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.headers().firstValue("Location")).hasValue("/api/orders/" + r.id());
        JsonNode o = r.json();
        assertThat(o.path("userId").asText()).isEqualTo(user);
        assertThat(o.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.path("items")).hasSize(2);
        assertThat(o.path("items").get(0).path("productId").asLong()).isEqualTo(p1);
        assertThat(o.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(o.path("items").get(0).path("unitPrice").asLong()).isEqualTo(12_000);
        assertThat(o.path("items").get(1).path("productId").asLong()).isEqualTo(p2);
        assertThat(o.path("items").get(1).path("quantity").asInt()).isEqualTo(3);
        assertThat(o.path("items").get(1).path("unitPrice").asLong()).isEqualTo(3_500);
        assertThat(o.path("couponCode").asText()).isEqualTo(coupon);
        assertThat(o.path("subtotal").asLong()).isEqualTo(34_500);
        assertThat(o.path("discount").asLong()).isEqualTo(1_000);
        assertThat(o.path("totalPrice").asLong()).isEqualTo(33_500);
        assertThat(o.has("paidAt")).isTrue();
        assertThat(o.path("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R3.5 조회: 같은 형태, couponCode·paidAt 은 없으면 null, expiresAt = createdAt + TTL(기본 15분)")
    void getOrder() {
        long productId = createProduct(5_000, 10);
        Resp created = createOrder(uniqueUser(), null, List.of(item(productId, 1)));

        Resp r = get("/api/orders/" + created.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json()).isEqualTo(created.json());
        assertThat(r.json().has("couponCode")).isTrue();
        assertThat(r.json().path("couponCode").isNull()).isTrue();
        assertThat(r.json().path("discount").asLong()).isZero();
        assertThat(r.json().path("totalPrice").asLong()).isEqualTo(5_000);
        assertThat(r.json().path("createdAt").asText()).matches(".*(Z|[+-]\\d\\d:\\d\\d)$");
        assertThat(Duration.between(instant(r.json().path("createdAt")), instant(r.json().path("expiresAt"))))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("R3.5 없는 주문은 404 ORDER_NOT_FOUND")
    void orderNotFound() {
        assertProblem(get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.4 생성 시 상품 reserved 가 수량만큼, 쿠폰 usedCount 가 1 늘어난다")
    void reservesStockAndUsesCoupon() {
        long p1 = createProduct(1_000, 10);
        long p2 = createProduct(2_000, 5);
        String coupon = createCoupon("FIXED", 100);

        placeOrder(uniqueUser(), coupon, item(p1, 4), item(p2, 5));

        assertThat(product(p1).path("reserved").asInt()).isEqualTo(4);
        assertThat(product(p1).path("available").asInt()).isEqualTo(6);
        assertThat(product(p2).path("reserved").asInt()).isEqualTo(5);
        assertThat(product(p2).path("available").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.3 없는 상품은 404 PRODUCT_NOT_FOUND, 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void notFoundReferences() {
        long productId = createProduct(1_000, 10);
        assertProblem(createOrder(uniqueUser(), null, List.of(item(987654321L, 1))), 404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), "NOSUCHCOUPON", List.of(item(productId, 1))), 404, "COUPON_NOT_FOUND");
        assertThat(product(productId).path("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 어느 항목이든 available 이 부족하면 409 INSUFFICIENT_STOCK")
    void insufficientStock() {
        long p1 = createProduct(1_000, 10);
        long p2 = createProduct(1_000, 2);
        placeOrder(item(p2, 1));

        assertProblem(createOrder(uniqueUser(), null, List.of(item(p1, 1), item(p2, 2))), 409, "INSUFFICIENT_STOCK");
        assertThat(createOrder(uniqueUser(), null, List.of(item(p1, 1), item(p2, 1))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.4 실패하면 예약·쿠폰 사용이 하나도 반영되지 않는다 (재고 부족)")
    void atomicOnStockFailure() {
        long p1 = createProduct(1_000, 10);
        long p2 = createProduct(1_000, 1);
        String coupon = createCoupon("FIXED", 100);

        assertProblem(createOrder(uniqueUser(), coupon, List.of(item(p1, 3), item(p2, 2))), 409, "INSUFFICIENT_STOCK");

        assertThat(product(p1).path("reserved").asInt()).isZero();
        assertThat(product(p2).path("reserved").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.4 실패하면 예약·쿠폰 사용이 하나도 반영되지 않는다 (쿠폰 적용 불가)")
    void atomicOnCouponFailure() {
        long p1 = createProduct(1_000, 10);
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
        body.put("minOrderAmount", 1_000_000);
        String coupon = createCoupon(body);

        assertProblem(createOrder(uniqueUser(), coupon, List.of(item(p1, 3))), 409, "COUPON_NOT_APPLICABLE");

        assertThat(product(p1).path("reserved").asInt()).isZero();
        assertThat(coupon(coupon).path("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("C3. 404 가 409 보다 먼저, 같은 단계의 409 는 재고 → 쿠폰 순")
    void errorPrecedence() {
        long scarce = createProduct(1_000, 1);
        Map<String, Object> body = couponBody(uniqueCouponCode(), "FIXED", 100);
        body.put("totalQuantity", 1);
        String exhausted = createCoupon(body);
        placeOrder(uniqueUser(), exhausted, item(createProduct(1_000, 1), 1));

        assertProblem(createOrder(uniqueUser(), exhausted, List.of(item(scarce, 2), item(987654321L, 1))),
                404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), "NOSUCHCOUPON", List.of(item(scarce, 2))), 404, "COUPON_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), exhausted, List.of(item(scarce, 2))), 409, "INSUFFICIENT_STOCK");
        assertProblem(createOrder(uniqueUser(), exhausted, List.of(item(scarce, 1))), 409, "COUPON_EXHAUSTED");
    }

    record Invalid(String name, Map<String, String> headers, Object body) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Invalid> invalidRequests() {
        Map<String, Object> validBody = orderBody(null, List.of(item(1, 1)));
        Map<String, String> validHeaders = Map.of("X-User-Id", "u1", "Idempotency-Key", "k1");
        List<Map<String, Object>> tooMany = IntStream.rangeClosed(1, 21).mapToObj(i -> item(i, 1)).toList();
        Map<String, Object> missingProductId = new HashMap<>(Map.of("items", List.of(Map.of("quantity", 1))));
        return Stream.of(
                new Invalid("X-User-Id 누락", Map.of("Idempotency-Key", "k1"), validBody),
                new Invalid("X-User-Id 공백", Map.of("X-User-Id", " \t", "Idempotency-Key", "k1"), validBody),
                new Invalid("X-User-Id 51자", Map.of("X-User-Id", "u".repeat(51), "Idempotency-Key", "k1"), validBody),
                new Invalid("Idempotency-Key 누락", Map.of("X-User-Id", "u1"), validBody),
                new Invalid("Idempotency-Key 65자", Map.of("X-User-Id", "u1", "Idempotency-Key", "k".repeat(65)), validBody),
                new Invalid("items 누락", validHeaders, Map.of()),
                new Invalid("items 0개", validHeaders, orderBody(null, List.of())),
                new Invalid("items 21개", validHeaders, orderBody(null, new ArrayList<>(tooMany))),
                new Invalid("quantity 0", validHeaders, orderBody(null, List.of(item(1, 0)))),
                new Invalid("quantity 1001", validHeaders, orderBody(null, List.of(item(1, 1001)))),
                new Invalid("productId 누락", validHeaders, missingProductId),
                new Invalid("productId 중복", validHeaders, orderBody(null, List.of(item(1, 1), item(1, 2)))),
                new Invalid("본문 JSON 파싱 실패", validHeaders, "{\"items\": ["),
                new Invalid("본문 없음", validHeaders, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("R3.2 헤더·본문 검증 위반은 400 VALIDATION_ERROR")
    void invalidRequest(Invalid invalid) {
        assertProblem(post("/api/orders", invalid.body(), invalid.headers()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 경계값(items 20개, quantity 1000, X-User-Id 50자, 키 64자)은 허용")
    void boundariesAccepted() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            items.add(item(createProduct(1, 10), 1));
        }
        items.add(item(createProduct(1, 1_000), 1_000));
        Resp r = post("/api/orders", orderBody(null, items),
                Map.of("X-User-Id", "u".repeat(50), "Idempotency-Key", uniqueKey() + "k".repeat(28)));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        assertThat(r.json().path("items")).hasSize(20);
    }
}
