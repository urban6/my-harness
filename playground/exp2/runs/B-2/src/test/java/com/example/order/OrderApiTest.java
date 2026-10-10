package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** R3. 주문 생성·조회 (+ C3 오류 우선순위) */
class OrderApiTest extends IntegrationTestSupport {

    @Test
    void create_returns201WithLocationAndBody() {
        long p1 = createProduct(3_000, 10);
        long p2 = createProduct(1_500, 10);
        String user = uniqueUser();

        Res res = createOrder(user, null, item(p1, 2), item(p2, 1));

        assertThat(res.status()).isEqualTo(201);
        JsonNode order = res.body();
        long id = order.get("id").asLong();
        assertThat(res.headers().getLocation().getPath()).isEqualTo("/api/orders/" + id);
        assertThat(order.get("userId").asText()).isEqualTo(user);
        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(order.get("items").get(0).get("unitPrice").asLong()).isEqualTo(3_000);
        assertThat(order.get("items").get(1).get("productId").asLong()).isEqualTo(p2);
        assertThat(order.get("items").get(1).get("unitPrice").asLong()).isEqualTo(1_500);
        assertThat(order.get("couponCode").isNull()).isTrue();
        assertThat(order.get("subtotal").asLong()).isEqualTo(7_500);
        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(7_500);
        assertThat(order.get("paidAt").isNull()).isTrue();

        OffsetDateTime createdAt = OffsetDateTime.parse(order.get("createdAt").asText());
        OffsetDateTime expiresAt = OffsetDateTime.parse(order.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));

        assertThat(get("/api/orders/" + id).body()).isEqualTo(order);
    }

    @Test
    void create_reservesStockAndUsesCoupon() {
        long p1 = createProduct(3_000, 10);
        long p2 = createProduct(1_500, 4);
        String code = createCoupon(Map.of());

        placeOrder(uniqueUser(), code, item(p1, 3), item(p2, 4));

        assertThat(product(p1).get("reserved").asInt()).isEqualTo(3);
        assertThat(product(p2).get("reserved").asInt()).isEqualTo(4);
        assertThat(product(p2).get("available").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void get_unknown_returns404() {
        assertProblem(get("/api/orders/999999999"), 404, "ORDER_NOT_FOUND");
    }

    // ---------- R3.2 검증 ----------

    @Test
    void create_missingOrInvalidHeaders_returns400() {
        long p = createProduct(1_000, 10);
        String body = toJson(orderBody(null, item(p, 1)));

        assertProblem(post("/api/orders", body, Map.of("Idempotency-Key", uniqueKey())), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", body, Map.of("X-User-Id", uniqueUser())), 400, "VALIDATION_ERROR");
        assertProblem(postOrder("   ", uniqueKey(), body), 400, "VALIDATION_ERROR");
        assertProblem(postOrder("u".repeat(51), uniqueKey(), body), 400, "VALIDATION_ERROR");
        assertProblem(postOrder(uniqueUser(), "k".repeat(65), body), 400, "VALIDATION_ERROR");
        assertProblem(postOrder(uniqueUser(), "", body), 400, "VALIDATION_ERROR");

        assertThat(postOrder("u".repeat(50), "k".repeat(64), body).status()).isEqualTo(201);
        assertThat(product(p).get("reserved").asInt()).isEqualTo(1);
    }

    @Test
    void create_invalidBody_returns400() {
        long p = createProduct(1_000, 10_000);
        List<Object> invalidBodies = new ArrayList<>(List.of(
                "{}",
                "{\"items\":[]}",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":0}]}",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1001}]}",
                "{\"items\":[{\"quantity\":1}]}",
                "{\"items\":[{\"productId\":" + p + "}]}",
                "{\"items\":[null]}",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1},{\"productId\":" + p + ",\"quantity\":2}]}",
                "{\"items\":",
                "not json"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(Map.of("productId", createProduct(100, 10), "quantity", 1));
        }
        Map<String, Object> tooManyBody = new HashMap<>();
        tooManyBody.put("items", tooMany);
        invalidBodies.add(tooManyBody);

        for (Object body : invalidBodies) {
            assertProblem(postOrder(uniqueUser(), uniqueKey(), body), 400, "VALIDATION_ERROR");
        }
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    void create_boundaryBody_returns201() {
        List<Map<String, Object>> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(Map.of("productId", createProduct(100, 1_000), "quantity", i == 0 ? 1_000 : 1));
        }

        assertThat(postOrder(uniqueUser(), uniqueKey(), Map.of("items", twenty)).status()).isEqualTo(201);
    }

    // ---------- R3.3 404 / 409 ----------

    @Test
    void create_unknownProduct_returns404() {
        long p = createProduct(1_000, 10);

        assertProblem(createOrder(uniqueUser(), null, item(p, 1), item(999_999_999L, 1)), 404, "PRODUCT_NOT_FOUND");
        assertThat(product(p).get("reserved").asInt()).isZero();
    }

    @Test
    void create_unknownCoupon_returns404() {
        long p = createProduct(1_000, 10);

        assertProblem(createOrder(uniqueUser(), "NOSUCHCOUPON", item(p, 1)), 404, "COUPON_NOT_FOUND");
    }

    @Test
    void create_insufficientStock_returns409() {
        long p = createProduct(1_000, 3);
        placeOrder(uniqueUser(), null, item(p, 2));

        assertProblem(createOrder(uniqueUser(), null, item(p, 2)), 409, "INSUFFICIENT_STOCK");
        assertThat(createOrder(uniqueUser(), null, item(p, 1)).status()).isEqualTo(201);
    }

    // ---------- R3.4 원자성 ----------

    @Test
    void create_isAllOrNothing_whenOneItemIsShort() {
        long enough = createProduct(1_000, 10);
        long shortage = createProduct(1_000, 1);
        String code = createCoupon(Map.of());

        assertProblem(createOrder(uniqueUser(), code, item(enough, 5), item(shortage, 2)), 409, "INSUFFICIENT_STOCK");

        assertThat(product(enough).get("reserved").asInt()).isZero();
        assertThat(product(shortage).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    @Test
    void create_isAllOrNothing_whenCouponNotApplicable() {
        long p = createProduct(1_000, 10);
        String code = createCoupon(Map.of("minOrderAmount", 1_000_000));

        assertProblem(createOrder(uniqueUser(), code, item(p, 2)), 409, "COUPON_NOT_APPLICABLE");

        assertThat(product(p).get("reserved").asInt()).isZero();
        assertThat(coupon(code).get("usedCount").asInt()).isZero();
    }

    // ---------- C3 오류 우선순위 ----------

    @Test
    void errorPrecedence_badRequestBeforeNotFound() {
        Res res = postOrder(uniqueUser(), uniqueKey(),
                "{\"items\":[{\"productId\":999999999,\"quantity\":0}],\"couponCode\":\"NOSUCHCOUPON\"}");

        assertProblem(res, 400, "VALIDATION_ERROR");
    }

    @Test
    void errorPrecedence_notFoundBeforeConflict() {
        long shortage = createProduct(1_000, 0);

        assertProblem(createOrder(uniqueUser(), null, item(shortage, 1), item(999_999_999L, 1)), 404, "PRODUCT_NOT_FOUND");
        assertProblem(createOrder(uniqueUser(), "NOSUCHCOUPON", item(shortage, 1)), 404, "COUPON_NOT_FOUND");
    }

    @Test
    void errorPrecedence_stockConflictBeforeCouponConflict() {
        long shortage = createProduct(1_000, 0);
        String exhausted = createCoupon(Map.of("totalQuantity", 1));
        placeOrder(uniqueUser(), exhausted, item(createProduct(1_000, 1), 1));

        assertProblem(createOrder(uniqueUser(), exhausted, item(shortage, 1)), 409, "INSUFFICIENT_STOCK");
    }
}
