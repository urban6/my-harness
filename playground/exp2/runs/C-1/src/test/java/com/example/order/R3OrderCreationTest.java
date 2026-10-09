package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("R3 주문 생성·조회")
class R3OrderCreationTest extends IntegrationTestBase {

    private long product(long price, int stock) {
        return createProduct("상품", price, stock).get("id").asLong();
    }

    private ResponseEntity<JsonNode> create(String user, String idemKey, Map<String, Object> body) {
        return createOrder(user, idemKey, body);
    }

    // ---------------- R3.1 ----------------

    @Test
    @DisplayName("R3.1 생성하면 201 + Location, status=PENDING_PAYMENT")
    void r3_1_create() {
        long p = product(15000, 10);

        ResponseEntity<JsonNode> res = order("user-1", null, p, 2);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode b = res.getBody();
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/orders/" + b.get("id").asLong());
        assertThat(b.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(b.get("userId").asText()).isEqualTo("user-1");
        assertThat(b.get("paidAt").isNull()).isTrue();
        assertThat(getOrder(b.get("id").asLong())).isEqualTo(b);
    }

    @Test
    @DisplayName("R3.1 X-User-Id 50자 / Idempotency-Key 64자 는 허용")
    void r3_1_headerBoundariesAccepted() {
        long p = product(1000, 10);
        Map<String, Object> body = orderBody(null, p, 1);

        assertThat(create("u".repeat(50), "k".repeat(64), body).getStatusCode().value()).isEqualTo(201);
        assertThat(create("x", "k", orderBody(null, p, 1)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.1/R3.2 X-User-Id 누락 -> 400")
    void r3_2_missingUserId() {
        long p = product(1000, 10);

        ResponseEntity<JsonNode> res = post("/api/orders", orderBody(null, p, 1), "Idempotency-Key", key());

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("R3.1/R3.2 Idempotency-Key 누락 -> 400")
    void r3_2_missingIdempotencyKey() {
        long p = product(1000, 10);

        ResponseEntity<JsonNode> res = post("/api/orders", orderBody(null, p, 1), "X-User-Id", "u1");

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("R3.2 X-User-Id 가 비었거나 공백뿐이거나 51자 -> 400")
    void r3_2_invalidUserId() {
        long p = product(1000, 10);

        assertProblem(create("", key(), orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertProblem(create("   ", key(), orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertProblem(create("u".repeat(51), key(), orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.2 Idempotency-Key 가 비었거나 65자 -> 400")
    void r3_2_invalidIdempotencyKey() {
        long p = product(1000, 10);

        assertProblem(create("u1", "", orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertProblem(create("u1", "k".repeat(65), orderBody(null, p, 1)), 400, "VALIDATION_ERROR");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    // ---------------- R3.2 본문 ----------------

    @Test
    @DisplayName("R3.2 items 가 비었거나 누락되었으면 400")
    void r3_2_itemsEmptyOrMissing() {
        assertProblem(create("u1", key(), new LinkedHashMap<>(Map.of("items", List.of()))), 400, "VALIDATION_ERROR");
        assertProblem(create("u1", key(), new LinkedHashMap<>(Map.of("couponCode", "ABCD"))), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 items 21개 -> 400, 20개 -> 201")
    void r3_2_itemsMax20() {
        long[] pq21 = new long[42];
        for (int i = 0; i < 21; i++) {
            pq21[2 * i] = i + 1;
            pq21[2 * i + 1] = 1;
        }
        assertProblem(create("u1", key(), orderBody(null, pq21)), 400, "VALIDATION_ERROR");

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(product(100, 5));
        }
        long[] pq20 = new long[40];
        for (int i = 0; i < 20; i++) {
            pq20[2 * i] = ids.get(i);
            pq20[2 * i + 1] = 1;
        }
        ResponseEntity<JsonNode> ok = create("u1", key(), orderBody(null, pq20));
        assertThat(ok.getStatusCode().value()).isEqualTo(201);
        assertThat(ok.getBody().get("items")).hasSize(20);
    }

    @Test
    @DisplayName("R3.2 quantity 0 / -1 / 1001 -> 400, 1000 -> 201")
    void r3_2_quantityRange() {
        long p = product(1000, 5000);

        assertProblem(order("u1", null, p, 0), 400, "VALIDATION_ERROR");
        assertProblem(order("u1", null, p, -1), 400, "VALIDATION_ERROR");
        assertProblem(order("u1", null, p, 1001), 400, "VALIDATION_ERROR");
        assertThat(order("u1", null, p, 1000).getStatusCode().value()).isEqualTo(201);
        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(1000);
    }

    @Test
    @DisplayName("R3.2 같은 productId 중복 -> 400")
    void r3_2_duplicateProductId() {
        long p = product(1000, 10);

        assertProblem(order("u1", null, p, 1, p, 2), 400, "VALIDATION_ERROR");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.2 productId/quantity 누락, quantity 소수, 깨진 JSON -> 400")
    void r3_2_malformedItems() {
        Map<String, Object> noProductId = new LinkedHashMap<>();
        noProductId.put("items", List.of(Map.of("quantity", 1)));
        Map<String, Object> noQuantity = new LinkedHashMap<>();
        noQuantity.put("items", List.of(Map.of("productId", 1)));
        Map<String, Object> fractional = new LinkedHashMap<>();
        fractional.put("items", List.of(Map.of("productId", 1, "quantity", 1.5)));

        assertProblem(create("u1", key(), noProductId), 400, "VALIDATION_ERROR");
        assertProblem(create("u1", key(), noQuantity), 400, "VALIDATION_ERROR");
        assertProblem(create("u1", key(), fractional), 400, "VALIDATION_ERROR");
        assertProblem(post("/api/orders", "{\"items\": [", "X-User-Id", "u1", "Idempotency-Key", key()), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 couponCode 생략 및 null 은 쿠폰 없는 주문")
    void r3_2_couponOptional() {
        long p = product(1000, 10);
        Map<String, Object> explicitNull = orderBody(null, p, 1);
        explicitNull.put("couponCode", null);

        ResponseEntity<JsonNode> omitted = order("u1", null, p, 1);
        ResponseEntity<JsonNode> nulled = create("u2", key(), explicitNull);

        assertThat(omitted.getStatusCode().value()).isEqualTo(201);
        assertThat(nulled.getStatusCode().value()).isEqualTo(201);
        assertThat(nulled.getBody().get("couponCode").isNull()).isTrue();
    }

    // ---------------- R3.3 ----------------

    @Test
    @DisplayName("R3.3 없는 상품 -> 404 PRODUCT_NOT_FOUND")
    void r3_3_productNotFound() {
        assertProblem(order("u1", null, 9999, 1), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.3 항목 중 하나라도 없는 상품이면 404 이고 다른 상품 예약은 반영되지 않는다")
    void r3_3_oneOfItemsMissing() {
        long p = product(1000, 10);

        assertProblem(order("u1", null, p, 1, 9999, 1), 404, "PRODUCT_NOT_FOUND");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 없는 쿠폰 -> 404 COUPON_NOT_FOUND")
    void r3_3_couponNotFound() {
        long p = product(1000, 10);

        assertProblem(order("u1", "NOSUCH01", p, 1), 404, "COUPON_NOT_FOUND");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 available 보다 많이 주문하면 409 INSUFFICIENT_STOCK")
    void r3_3_insufficientStock() {
        long p = product(1000, 5);

        assertProblem(order("u1", null, p, 6), 409, "INSUFFICIENT_STOCK");
        assertThat(getProduct(p).get("reserved").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 available 과 정확히 같은 수량은 허용되고, 이후 1개는 INSUFFICIENT_STOCK")
    void r3_3_exactAvailable() {
        long p = product(1000, 5);

        assertThat(order("u1", null, p, 5).getStatusCode().value()).isEqualTo(201);

        assertProblem(order("u2", null, p, 1), 409, "INSUFFICIENT_STOCK");
        assertThat(getProduct(p).get("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 이미 예약된 수량을 뺀 available 로 판단한다 (stock 5, 예약 3 -> 3개 요청은 409)")
    void r3_3_availabilityConsidersReserved() {
        long p = product(1000, 5);
        orderOk("u1", null, p, 3);

        assertProblem(order("u2", null, p, 3), 409, "INSUFFICIENT_STOCK");
        assertThat(order("u2", null, p, 2).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.3 stock 0 인 상품은 수량 1 도 409")
    void r3_3_zeroStock() {
        long p = product(1000, 0);

        assertProblem(order("u1", null, p, 1), 409, "INSUFFICIENT_STOCK");
    }

    // ---------------- R3.4 ----------------

    @Test
    @DisplayName("R3.4 생성하면 각 상품 reserved 가 주문 수량만큼, 쿠폰 usedCount 가 1 늘고 stock 은 불변")
    void r3_4_reserveAndUseCoupon() {
        long p1 = product(1000, 10);
        long p2 = product(2000, 10);
        createCoupon("ATOMIC01", "FIXED", 500);

        orderOk("u1", "ATOMIC01", p1, 3, p2, 4);

        assertThat(getProduct(p1).get("reserved").asInt()).isEqualTo(3);
        assertThat(getProduct(p2).get("reserved").asInt()).isEqualTo(4);
        assertThat(getProduct(p1).get("stock").asInt()).isEqualTo(10);
        assertThat(getCoupon("ATOMIC01").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 두 번째 상품 재고 부족이면 첫 상품 reserved 와 쿠폰 usedCount 모두 불변, 주문 없음")
    void r3_4_atomicOnInsufficientStock() {
        long p1 = product(1000, 10);
        long p2 = product(2000, 1);
        createCoupon("ATOMIC02", "FIXED", 500);

        ResponseEntity<JsonNode> res = order("u1", "ATOMIC02", p1, 5, p2, 2);

        assertProblem(res, 409, "INSUFFICIENT_STOCK");
        assertThat(getProduct(p1).get("reserved").asInt()).isZero();
        assertThat(getProduct(p2).get("reserved").asInt()).isZero();
        assertThat(getCoupon("ATOMIC02").get("usedCount").asInt()).isZero();
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 적용 불가(최소 금액 미달)면 상품 reserved 도 반영되지 않는다")
    void r3_4_atomicOnCouponNotApplicable() {
        long p1 = product(1000, 10);
        long p2 = product(1000, 10);
        Map<String, Object> c = couponBody("ATOMIC03", "FIXED", 500);
        c.put("minOrderAmount", 1_000_000);
        createCoupon(c);

        assertProblem(order("u1", "ATOMIC03", p1, 1, p2, 1), 409, "COUPON_NOT_APPLICABLE");

        assertThat(getProduct(p1).get("reserved").asInt()).isZero();
        assertThat(getProduct(p2).get("reserved").asInt()).isZero();
        assertThat(getCoupon("ATOMIC03").get("usedCount").asInt()).isZero();
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 소진되었으면 상품 reserved 도 반영되지 않는다")
    void r3_4_atomicOnCouponExhausted() {
        long p = product(1000, 10);
        Map<String, Object> c = couponBody("ATOMIC04", "FIXED", 500);
        c.put("totalQuantity", 1);
        createCoupon(c);
        orderOk("u1", "ATOMIC04", p, 1);

        assertProblem(order("u2", "ATOMIC04", p, 4), 409, "COUPON_EXHAUSTED");

        assertThat(getProduct(p).get("reserved").asInt()).isEqualTo(1);
        assertThat(getCoupon("ATOMIC04").get("usedCount").asInt()).isEqualTo(1);
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 존재하지 않는 쿠폰이면 상품 reserved 는 반영되지 않는다")
    void r3_4_atomicOnCouponNotFound() {
        long p = product(1000, 10);

        assertProblem(order("u1", "NOSUCH02", p, 3), 404, "COUPON_NOT_FOUND");

        assertThat(getProduct(p).get("reserved").asInt()).isZero();
        assertThat(countOrders()).isZero();
    }

    // ---------------- R3.5 ----------------

    @Test
    @DisplayName("R3.5 응답 형태: 필드 집합, items 순서·unitPrice, 시각은 오프셋 포함 ISO-8601")
    void r3_5_shape() {
        long p1 = product(15000, 10);
        long p2 = product(30000, 10);
        createCoupon("SHAPE001", "FIXED", 5000);

        JsonNode created = orderOk("shape-user", "SHAPE001", p2, 1, p1, 2);
        JsonNode b = getOrder(created.get("id").asLong());

        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(b.get("userId").asText()).isEqualTo("shape-user");
        assertThat(b.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(b.get("couponCode").asText()).isEqualTo("SHAPE001");
        assertThat(b.get("subtotal").asLong()).isEqualTo(60000);
        assertThat(b.get("discount").asLong()).isEqualTo(5000);
        assertThat(b.get("totalPrice").asLong()).isEqualTo(55000);
        assertThat(b.get("paidAt").isNull()).isTrue();
        JsonNode items = b.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(p2);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(30000);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(p1);
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("unitPrice").asLong()).isEqualTo(15000);
        // 오프셋이 포함된 문자열이어야 파싱된다
        OffsetDateTime.parse(b.get("createdAt").asText());
        OffsetDateTime.parse(b.get("expiresAt").asText());
    }

    @Test
    @DisplayName("R3.5 expiresAt = createdAt + ORDER_PAYMENT_TTL(기본 PT15M), createdAt 은 요청 시각 근처")
    void r3_5_expiresAtIsCreatedAtPlusTtl() {
        long p = product(1000, 10);
        Instant before = Instant.now().minusSeconds(1);

        JsonNode b = orderOk("u1", null, p, 1);

        Instant createdAt = OffsetDateTime.parse(b.get("createdAt").asText()).toInstant();
        Instant expiresAt = OffsetDateTime.parse(b.get("expiresAt").asText()).toInstant();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
        assertThat(createdAt).isBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("R3.5 없는 주문 -> 404 ORDER_NOT_FOUND")
    void r3_5_notFound() {
        assertProblem(get("/api/orders/9999"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.5 쿠폰 없는 주문의 couponCode 는 null")
    void r3_5_noCouponIsNull() {
        long p = product(1000, 10);

        JsonNode b = getOrder(orderOk("u1", null, p, 1).get("id").asLong());

        assertThat(b.has("couponCode")).isTrue();
        assertThat(b.get("couponCode").isNull()).isTrue();
        assertThat(b.has("paidAt")).isTrue();
        assertThat(b.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R3.5 결제 후 paidAt 은 채워지고 createdAt 이후의 시각이다")
    void r3_5_paidAtAfterPayment() {
        long p = product(1000, 10);
        JsonNode created = orderOk("u1", null, p, 1);

        payOk(created.get("id").asLong());

        JsonNode b = getOrder(created.get("id").asLong());
        Instant paidAt = OffsetDateTime.parse(b.get("paidAt").asText()).toInstant();
        assertThat(paidAt).isAfterOrEqualTo(OffsetDateTime.parse(b.get("createdAt").asText()).toInstant());
    }
}
