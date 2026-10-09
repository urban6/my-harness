package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("R3 주문 생성·조회")
class OrderCreateApiTest extends IntegrationTest {

    @Test
    @DisplayName("R3.1/R3.5 생성하면 201 + Location, PENDING_PAYMENT, 주문 시점 단가와 금액")
    void create_returns201() {
        long p1 = createProduct(12_000, 10);
        long p2 = createProduct(3_500, 10);
        String user = uniqueUser();

        ApiResponse res = placeOrder(user, null, List.of(item(p1, 2), item(p2, 3)));

        assertThat(res.status()).isEqualTo(201);
        long id = res.id();
        assertThat(res.header("Location")).endsWith("/api/orders/" + id);
        JsonNode o = res.body();
        assertThat(o.get("userId").asText()).isEqualTo(user);
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("items")).hasSize(2);
        assertThat(o.at("/items/0/productId").asLong()).isEqualTo(p1);
        assertThat(o.at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(o.at("/items/0/unitPrice").asLong()).isEqualTo(12_000);
        assertThat(o.at("/items/1/productId").asLong()).isEqualTo(p2);
        assertThat(o.get("couponCode").isNull()).isTrue();
        assertThat(o.get("subtotal").asLong()).isEqualTo(34_500);
        assertThat(o.get("discount").asLong()).isZero();
        assertThat(o.get("totalPrice").asLong()).isEqualTo(34_500);
        assertThat(o.get("paidAt").isNull()).isTrue();
        // R3.5: expiresAt = createdAt + ORDER_PAYMENT_TTL(기본 PT15M), C2: 오프셋 포함 ISO-8601
        OffsetDateTime createdAt = OffsetDateTime.parse(o.get("createdAt").asText());
        OffsetDateTime expiresAt = OffsetDateTime.parse(o.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("R3.5 조회는 생성 응답과 같은 형태·값")
    void get_returnsOrder() {
        long productId = createProduct(1_000, 10);
        String code = createCoupon(Map.of("value", 300));
        ApiResponse created = placeOrder(uniqueUser(), code, List.of(item(productId, 1)));

        ApiResponse res = api.get("/api/orders/" + created.id());

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(created.body());
        assertThat(res.body().properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(res.body().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R3.5 없는 주문은 404 ORDER_NOT_FOUND")
    void get_returns404() {
        assertProblem(api.get("/api/orders/999999999"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.4 쿠폰 할인이 주문 금액에 반영된다(RATE floor, max 상한, C1 int 초과 금액)")
    void appliesCouponDiscount() {
        long expensive = createProduct(10_000_000, 1_000);
        String rate = createCoupon(Map.of("type", "RATE", "value", 15, "maxDiscountAmount", 100_000_000_000L));

        JsonNode o = placeOrder(uniqueUser(), rate, List.of(item(expensive, 1_000))).body();

        assertThat(o.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.get("discount").asLong()).isEqualTo(1_500_000_000L);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(8_500_000_000L);

        long cheap = createProduct(999, 10);
        String capped = createCoupon(Map.of("type", "RATE", "value", 15, "maxDiscountAmount", 100));
        JsonNode o2 = placeOrder(uniqueUser(), capped, List.of(item(cheap, 1))).body();
        assertThat(o2.get("discount").asLong()).isEqualTo(100); // floor(149.85)=149 → 상한 100

        String fixed = createCoupon(Map.of("type", "FIXED", "value", 5_000));
        JsonNode o3 = placeOrder(uniqueUser(), fixed, List.of(item(cheap, 2))).body();
        assertThat(o3.get("discount").asLong()).isEqualTo(1_998); // subtotal 상한
        assertThat(o3.get("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.4 생성 시 reserved 가 수량만큼, 쿠폰 usedCount 가 1 늘어난다")
    void reservesStockAndUsesCoupon() {
        long p1 = createProduct(1_000, 10);
        long p2 = createProduct(1_000, 10);
        String code = createCoupon(Map.of());

        createOrder(uniqueUser(), code, List.of(item(p1, 4), item(p2, 1)));

        assertProduct(p1, 10, 4);
        assertProduct(p2, 10, 1);
        assertThat(usedCount(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.3 없는 상품은 404 PRODUCT_NOT_FOUND, 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void notFound() {
        long productId = createProduct(1_000, 10);

        assertProblem(placeOrder(uniqueUser(), null, List.of(item(productId, 1), item(999_999_999L, 1))),
                404, "PRODUCT_NOT_FOUND");
        assertProblem(placeOrder(uniqueUser(), "NOSUCHCOUPON", List.of(item(productId, 1))),
                404, "COUPON_NOT_FOUND");
        assertProduct(productId, 10, 0);
    }

    @Test
    @DisplayName("R3.3/R3.4 어느 항목이든 available 이 부족하면 409, 다른 항목 예약·쿠폰 사용도 반영되지 않는다")
    void insufficientStock_isAllOrNothing() {
        long enough = createProduct(1_000, 10);
        long scarce = createProduct(1_000, 2);
        String code = createCoupon(Map.of());

        ApiResponse res = placeOrder(uniqueUser(), code, List.of(item(enough, 5), item(scarce, 3)));

        assertProblem(res, 409, "INSUFFICIENT_STOCK");
        assertProduct(enough, 10, 0);
        assertProduct(scarce, 2, 0);
        assertThat(usedCount(code)).isZero();
    }

    @Test
    @DisplayName("R3.3 available 은 예약분을 뺀 수량으로 판정한다")
    void insufficientStock_considersReserved() {
        long productId = createProduct(1_000, 5);
        createOrder(uniqueUser(), null, List.of(item(productId, 4)));

        assertProblem(placeOrder(uniqueUser(), null, List.of(item(productId, 2))), 409, "INSUFFICIENT_STOCK");
        assertThat(placeOrder(uniqueUser(), null, List.of(item(productId, 1))).status()).isEqualTo(201);
    }

    static Stream<Arguments> invalidRequests() {
        String user = "user-1";
        List<Map<String, Object>> twentyOne = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            twentyOne.add(Map.of("productId", i, "quantity", 1));
        }
        String valid = "{\"items\":[{\"productId\":1,\"quantity\":1}]}";
        return Stream.of(
                Arguments.of("X-User-Id 누락", null, "k1", valid),
                Arguments.of("X-User-Id 공백", "   ", "k1", valid),
                Arguments.of("X-User-Id 51자", "u".repeat(51), "k1", valid),
                Arguments.of("Idempotency-Key 누락", user, null, valid),
                Arguments.of("Idempotency-Key 65자", user, "k".repeat(65), valid),
                Arguments.of("items 누락", user, "k1", "{}"),
                Arguments.of("items 비어 있음", user, "k1", "{\"items\":[]}"),
                Arguments.of("items 21개", user, "k1",
                        ApiClient.toJson(Map.of("items", twentyOne))),
                Arguments.of("quantity 0", user, "k1", "{\"items\":[{\"productId\":1,\"quantity\":0}]}"),
                Arguments.of("quantity 1001", user, "k1", "{\"items\":[{\"productId\":1,\"quantity\":1001}]}"),
                Arguments.of("quantity 누락", user, "k1", "{\"items\":[{\"productId\":1}]}"),
                Arguments.of("productId 누락", user, "k1", "{\"items\":[{\"quantity\":1}]}"),
                Arguments.of("productId 중복", user, "k1",
                        "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}"),
                Arguments.of("item null", user, "k1", "{\"items\":[null]}"),
                Arguments.of("JSON 파싱 실패", user, "k1", "{\"items\":["),
                Arguments.of("본문 없음", user, "k1", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("R3.2 헤더·본문 규칙 위반은 400")
    void create_rejectsInvalid(String name, String userId, String key, String body) {
        ApiResponse res = api.postRaw("/api/orders", body, "X-User-Id", userId, "Idempotency-Key", key);
        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 경계값(X-User-Id 50자, 키 64자, 항목 20개, 수량 1,000)과 couponCode 생략은 허용")
    void create_acceptsBoundaries() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item(createProduct(100, 1_000), i == 0 ? 1_000 : 1));
        }
        String user = ("b" + uniqueUser()).repeat(5).substring(0, 50);
        String key = (uniqueKey() + uniqueKey()).substring(0, 64);

        ApiResponse res = placeOrder(user, key, Map.of("items", items));

        assertThat(res.status()).as(res.toString()).isEqualTo(201);
        assertThat(res.body().get("items")).hasSize(20);
    }

    @Test
    @DisplayName("C3 400 이 404 보다, 404 가 409 보다, 재고 409 가 쿠폰 409 보다 먼저다")
    void errorPrecedence() {
        long scarce = createProduct(1_000, 1);
        String exhausted = createCoupon(Map.of("totalQuantity", 1));
        createOrder(uniqueUser(), exhausted, List.of(item(createProduct(1_000, 1), 1)));

        // 400(수량 위반) + 404(없는 상품)
        assertProblem(placeOrder(uniqueUser(), null, List.of(item(999_999_999L, 0))), 400, "VALIDATION_ERROR");
        // 404(없는 상품) + 409(재고 부족)
        assertProblem(placeOrder(uniqueUser(), null, List.of(item(scarce, 5), item(999_999_999L, 1))),
                404, "PRODUCT_NOT_FOUND");
        // 404(없는 쿠폰) + 409(재고 부족)
        assertProblem(placeOrder(uniqueUser(), "NOSUCHCOUPON", List.of(item(scarce, 5))), 404, "COUPON_NOT_FOUND");
        // 409 재고 + 409 쿠폰 소진 → 재고
        assertProblem(placeOrder(uniqueUser(), exhausted, List.of(item(scarce, 5))), 409, "INSUFFICIENT_STOCK");
    }
}
