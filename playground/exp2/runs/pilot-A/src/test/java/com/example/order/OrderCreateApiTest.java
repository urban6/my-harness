package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api;
import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R3 주문 생성·조회")
class OrderCreateApiTest extends IntegrationTest {

    @Test
    @DisplayName("R3.1/R3.5 생성하면 201 + Location, 조회와 같은 형태 (PENDING_PAYMENT)")
    void createAndGet() {
        long p = createProduct(1200, 10);
        long q = createProduct(800, 10);
        String user = uniqueUser();

        Response created = postOrder(user, uniqueKey(), orderBody(null, q, 2, p, 1));

        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode body = created.body();
        long id = body.get("id").asLong();
        assertThat(created.location()).endsWith("/api/orders/" + id);
        assertThat(body.get("userId").asText()).isEqualTo(user);
        assertThat(body.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(q);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(body.get("items").get(0).get("unitPrice").asLong()).isEqualTo(800);
        assertThat(body.get("items").get(1).get("productId").asLong()).isEqualTo(p);
        assertThat(body.get("couponCode").isNull()).isTrue();
        assertThat(body.get("subtotal").asLong()).isEqualTo(2800);
        assertThat(body.get("discount").asLong()).isZero();
        assertThat(body.get("totalPrice").asLong()).isEqualTo(2800);
        assertThat(body.get("paidAt").isNull()).isTrue();

        OffsetDateTime createdAt = OffsetDateTime.parse(body.get("createdAt").asText());
        OffsetDateTime expiresAt = OffsetDateTime.parse(body.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));

        assertThat(order(id)).isEqualTo(body);
    }

    @Test
    @DisplayName("R3.4 생성 시 reserved가 수량만큼, 쿠폰 usedCount가 1 늘어난다")
    void reservesStockAndCoupon() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 10);

        JsonNode order = createOrder(uniqueUser(), code, p, 4);

        assertThat(order.get("couponCode").asText()).isEqualTo(code);
        assertThat(order.get("discount").asLong()).isEqualTo(100);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(4);
        assertThat(product(p).get("available").asLong()).isEqualTo(6);
        assertThat(coupon(code).get("usedCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.5 unitPrice는 주문 시점 가격, 금액은 int 범위를 넘을 수 있다")
    void largeAmounts() {
        long a = createProduct(10_000_000, 1000);
        long b = createProduct(10_000_000, 1000);
        long c = createProduct(10_000_000, 1000);

        JsonNode order = createOrder(uniqueUser(), null, a, 1000, b, 1000, c, 1000);

        assertThat(order.get("subtotal").asLong()).isEqualTo(30_000_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(30_000_000_000L);
        assertThat(order.get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000_000);
    }

    @Test
    @DisplayName("R3.2 헤더 누락·위반은 400")
    void invalidHeaders() {
        long p = createProduct(1000, 10);
        String body = orderBody(null, p, 1);

        assertProblem(api.post("/api/orders", body, "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(api.post("/api/orders", body, "X-User-Id", uniqueUser()), 400, "VALIDATION_ERROR");
        assertProblem(postOrder("   ", uniqueKey(), body), 400, "VALIDATION_ERROR");
        assertProblem(postOrder("u".repeat(51), uniqueKey(), body), 400, "VALIDATION_ERROR");
        assertProblem(postOrder(uniqueUser(), "k".repeat(65), body), 400, "VALIDATION_ERROR");

        assertThat(postOrder("u".repeat(50), "k".repeat(64), body).status()).isEqualTo(201);
        assertThat(product(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.2 items·quantity·productId 규칙 위반은 400")
    void invalidBody() {
        long p = createProduct(1000, 10);
        List<String> invalid = new ArrayList<>(List.of(
                "{\"items\":[]}",
                "{}",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":0}]}",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1001}]}",
                "{\"items\":[{\"productId\":" + p + "}]}",
                "{\"items\":[{\"quantity\":1}]}",
                "{\"items\":[null]}",
                orderBody(null, p, 1, p, 2),
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}],\"couponCode\":\"  \"}",
                "{\"items\":"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(Map.of("productId", createProduct(100, 10), "quantity", 1));
        }
        invalid.add(Api.json(Map.of("items", tooMany)));

        for (String body : invalid) {
            assertProblem(postOrder(uniqueUser(), uniqueKey(), body), 400, "VALIDATION_ERROR");
        }
        assertThat(product(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.2 items 20개, quantity 1000은 허용")
    void boundaries() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(Map.of("productId", createProduct(100, 1000), "quantity", i == 0 ? 1000 : 1));
        }
        assertThat(postOrder(uniqueUser(), uniqueKey(), Api.json(Map.of("items", items))).status())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("R3.3 없는 상품은 404 PRODUCT_NOT_FOUND, 없는 쿠폰은 404 COUPON_NOT_FOUND")
    void notFound() {
        long p = createProduct(1000, 10);
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(null, p, 1, 987654321L, 1)), 404,
                "PRODUCT_NOT_FOUND");
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody("NOSUCHCOUPON", p, 1)), 404,
                "COUPON_NOT_FOUND");
        assertThat(product(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.3/R3.4 한 항목이라도 재고가 부족하면 409 INSUFFICIENT_STOCK, 아무것도 반영되지 않는다")
    void insufficientStockIsAllOrNothing() {
        long p = createProduct(1000, 10);
        long q = createProduct(1000, 2);
        String code = createCoupon("FIXED", 100, 10);

        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 5, q, 3)), 409,
                "INSUFFICIENT_STOCK");

        assertThat(product(p).get("reserved").asLong()).isZero();
        assertThat(product(q).get("reserved").asLong()).isZero();
        assertThat(coupon(code).get("usedCount").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.3 이미 예약된 수량 때문에 available이 부족해도 409")
    void insufficientAvailable() {
        long p = createProduct(1000, 5);
        createOrder(uniqueUser(), null, p, 4);
        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(null, p, 2)), 409, "INSUFFICIENT_STOCK");
        createOrder(uniqueUser(), null, p, 1);
        assertThat(product(p).get("available").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰 검사가 실패하면 재고 예약도 반영되지 않는다")
    void couponFailureRollsBackReservation() {
        long p = createProduct(1000, 10);
        String code = createCoupon("FIXED", 100, 1);
        createOrder(uniqueUser(), code, p, 1);

        assertProblem(postOrder(uniqueUser(), uniqueKey(), orderBody(code, p, 3)), 409, "COUPON_EXHAUSTED");
        assertThat(product(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.5 없는 주문은 404 ORDER_NOT_FOUND")
    void getMissing() {
        assertProblem(api.get("/api/orders/987654321"), 404, "ORDER_NOT_FOUND");
    }
}
