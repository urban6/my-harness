package com.example.order;

import static com.example.order.support.TestApi.item;
import static com.example.order.support.TestApi.newKey;
import static com.example.order.support.TestApi.newUserId;
import static com.example.order.support.TestApi.orderBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.example.order.support.TestApi.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R3 주문 생성·조회")
class OrderCreateApiTest extends IntegrationTest {

    @Test
    @DisplayName("R3.1·R3.5 주문 생성 → 201 + Location, PENDING_PAYMENT, 응답 형태")
    void create_returns201() {
        long p1 = api.createProduct(1_500, 10);
        long p2 = api.createProduct(700, 10);
        String user = newUserId();

        Response response = api.createOrder(user, null, List.of(item(p1, 2), item(p2, 3))).assertStatus(201);

        JsonNode body = response.body();
        assertThat(response.header("Location")).endsWith("/api/orders/" + body.get("id").asLong());
        assertThat(body.get("userId").asText()).isEqualTo(user);
        assertThat(body.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1_500);
        assertThat(body.get("items").get(1).get("unitPrice").asLong()).isEqualTo(700);
        assertThat(body.has("couponCode") && body.get("couponCode").isNull()).isTrue();
        assertThat(body.get("subtotal").asLong()).isEqualTo(5_100);
        assertThat(body.get("discount").asLong()).isZero();
        assertThat(body.get("totalPrice").asLong()).isEqualTo(5_100);
        assertThat(body.has("paidAt") && body.get("paidAt").isNull()).isTrue();

        Instant createdAt = Instant.parse(body.get("createdAt").asText());
        Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
        assertThat(body.get("createdAt").asText()).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
    }

    @Test
    @DisplayName("R3.5 주문 조회 → 생성 응답과 같은 형태, unitPrice는 주문 시점 가격, 없으면 404")
    void get_returnsOrder() {
        long productId = api.createProduct(2_000, 10);
        String code = api.createCoupon("FIXED", 500);
        JsonNode created = api.createOrder(newUserId(), code, List.of(item(productId, 1))).assertStatus(201).body();

        JsonNode fetched = api.get("/api/orders/" + created.get("id").asLong()).assertStatus(200).body();

        assertThat(fetched).isEqualTo(created);
        assertThat(fetched.get("couponCode").asText()).isEqualTo(code);
        assertThat(fetched.get("discount").asLong()).isEqualTo(500);
        api.get("/api/orders/999999999").assertProblem(404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("C1 금액은 int 범위를 넘을 수 있다")
    void amounts_exceedIntRange() {
        long productId = api.createProduct(10_000_000, 1_000);
        JsonNode body = api.createOrder(newUserId(), null, List.of(item(productId, 1_000))).assertStatus(201).body();
        assertThat(body.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(body.get("totalPrice").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("R3.2 헤더 누락·위반 → 400")
    void create_invalidHeaders_return400() {
        long productId = api.createProduct(1000, 10);
        Map<String, Object> body = orderBody(null, List.of(item(productId, 1)));

        api.post("/api/orders", body, Map.of("Idempotency-Key", newKey())).assertProblem(400, "VALIDATION_ERROR");
        api.post("/api/orders", body, Map.of("X-User-Id", newUserId())).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder("   ", newKey(), body).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder("u".repeat(51), newKey(), body).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(newUserId(), "k".repeat(65), body).assertProblem(400, "VALIDATION_ERROR");

        api.createOrder("u".repeat(50), "k".repeat(64), body).assertStatus(201);
        assertThat(api.product(productId).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.2 본문 검증 위반 → 400")
    void create_invalidBody_returns400() {
        long productId = api.createProduct(1000, 10_000);
        String user = newUserId();

        List<Map<String, Object>> tooMany = new ArrayList<>();
        IntStream.range(0, 21).forEach(i -> tooMany.add(item(api.createProduct(100, 10), 1)));
        Map<String, Object> nullProduct = new HashMap<>();
        nullProduct.put("productId", null);
        nullProduct.put("quantity", 1);

        api.createOrder(user, newKey(), orderBody(null, List.of())).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), Map.of()).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), orderBody(null, tooMany)).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), orderBody(null, List.of(item(productId, 0)))).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), orderBody(null, List.of(item(productId, 1001)))).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), orderBody(null, List.of(nullProduct))).assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), orderBody(null, List.of(item(productId, 1), item(productId, 2))))
                .assertProblem(400, "VALIDATION_ERROR");
        api.createOrder(user, newKey(), "{\"items\": [").assertProblem(400, "VALIDATION_ERROR");

        // 경계값: 20개, 수량 1000
        api.createOrder(user, newKey(), orderBody(null, tooMany.subList(0, 20))).assertStatus(201);
        api.createOrder(user, newKey(), orderBody(null, List.of(item(productId, 1000)))).assertStatus(201);
    }

    @Test
    @DisplayName("R3.3 없는 상품·쿠폰 → 404")
    void create_unknownProductOrCoupon_returns404() {
        long productId = api.createProduct(1000, 10);

        api.createOrder(newUserId(), null, List.of(item(productId, 1), item(999_999_999L, 1)))
                .assertProblem(404, "PRODUCT_NOT_FOUND");
        api.createOrder(newUserId(), "NOSUCH01", List.of(item(productId, 1)))
                .assertProblem(404, "COUPON_NOT_FOUND");
        assertThat(api.product(productId).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3·R3.4 한 항목이라도 재고 부족 → 409, 예약·쿠폰 사용은 전혀 반영되지 않음")
    void create_insufficientStock_isAtomic() {
        long enough = api.createProduct(1000, 10);
        long scarce = api.createProduct(1000, 2);
        String code = api.createCoupon("FIXED", 100);

        api.createOrder(newUserId(), code, List.of(item(enough, 5), item(scarce, 3)))
                .assertProblem(409, "INSUFFICIENT_STOCK");

        assertThat(api.product(enough).get("reserved").asInt()).isZero();
        assertThat(api.product(scarce).get("reserved").asInt()).isZero();
        assertThat(api.coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.4 생성 시 reserved가 수량만큼, 쿠폰 usedCount가 1 증가")
    void create_reservesStockAndUsesCoupon() {
        long p1 = api.createProduct(1000, 10);
        long p2 = api.createProduct(1000, 10);
        String code = api.createCoupon("FIXED", 100);

        api.createOrder(newUserId(), code, List.of(item(p1, 4), item(p2, 10))).assertStatus(201);

        assertThat(api.product(p1).get("reserved").asInt()).isEqualTo(4);
        assertThat(api.product(p2).get("reserved").asInt()).isEqualTo(10);
        assertThat(api.product(p2).get("available").asInt()).isZero();
        assertThat(api.coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }
}
