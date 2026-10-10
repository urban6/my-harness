package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R3. 주문 생성·조회")
class R3OrderCreateTest extends IntegrationTestBase {

    // ---------------------------------------------------------------- R3.1

    @Test
    @DisplayName("R3.1 주문을 생성하면 201, Location(/api/orders/{id}), status=PENDING_PAYMENT")
    void r3_1_create_returns201WithLocationAndPendingStatus() {
        long productId = product(10_000, 5);
        String user = uid("u");

        ResponseEntity<String> r = createOrder(user, uid("k"), orderBody(null, item(productId, 2)));

        assertThat(statusOf(r)).isEqualTo(201);
        JsonNode o = json(r);
        assertThat(r.getHeaders().getLocation()).hasToString("/api/orders/" + o.get("id").asLong());
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("userId").asText()).isEqualTo(user);
    }

    @Test
    @DisplayName("R3.1 X-User-Id 헤더가 없으면 400")
    void r3_1_missingUserIdHeader_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = post("/api/orders", orderBody(null, item(productId, 1)), "Idempotency-Key", uid("k"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 X-User-Id 가 공백뿐이면 400")
    void r3_1_blankUserIdHeader_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = createOrder("   ", uid("k"), orderBody(null, item(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 X-User-Id 가 51자이면 400")
    void r3_1_userId51chars_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = createOrder("u".repeat(51), uid("k"), orderBody(null, item(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 X-User-Id 가 정확히 50자이면 허용")
    void r3_1_userId50chars_returns201() {
        long productId = product(10_000, 5);
        String user = "u" + java.util.UUID.randomUUID().toString().replace("-", "") + "123456789012345678";
        user = user.substring(0, 50);

        ResponseEntity<String> r = createOrder(user, uid("k"), orderBody(null, item(productId, 1)));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(json(r).get("userId").asText()).hasSize(50);
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 헤더가 없으면 400")
    void r3_1_missingIdempotencyKey_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = post("/api/orders", orderBody(null, item(productId, 1)), "X-User-Id", uid("u"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 가 65자이면 400")
    void r3_1_idempotencyKey65chars_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = createOrder(uid("u"), "k".repeat(65), orderBody(null, item(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 가 정확히 64자이면 허용")
    void r3_1_idempotencyKey64chars_returns201() {
        long productId = product(10_000, 5);
        String key = (uid("k") + "x".repeat(64)).substring(0, 64);

        ResponseEntity<String> r = createOrder(uid("u"), key, orderBody(null, item(productId, 1)));

        assertThat(statusOf(r)).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 가 공백뿐이면 400")
    void r3_1_blankIdempotencyKey_returns400() {
        long productId = product(10_000, 5);

        ResponseEntity<String> r = createOrder(uid("u"), "   ", orderBody(null, item(productId, 1)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 요청 본문 JSON 이 깨져 있으면 400")
    void r3_1_malformedJson_returns400() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), "{\"items\":[{\"productId\":1,");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 요청 본문이 비어 있으면 400")
    void r3_1_emptyBody_returns400() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), null);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R3.2

    @Test
    @DisplayName("R3.2 items 가 비어 있으면 400")
    void r3_2_emptyItems_returns400() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), Map.of("items", List.of()));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 items 가 없으면 400")
    void r3_2_missingItems_returns400() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), Map.of("couponCode", "ABCD"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 items 가 정확히 20개이면 허용")
    void r3_2_items20_returns201() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item(product(1_000, 5), 1));
        }

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), Map.of("items", items));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(json(r).get("items")).hasSize(20);
    }

    @Test
    @DisplayName("R3.2 items 가 21개이면 400 (상품 존재 여부보다 먼저)")
    void r3_2_items21_returns400() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            items.add(item(900_000_000L + i, 1));
        }

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), Map.of("items", items));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] quantity={0}")
    @CsvSource({"0", "-1", "1001", "100000"})
    @DisplayName("R3.2 quantity 가 1 ~ 1,000 밖이면 400")
    void r3_2_quantityOutOfRange_returns400(int quantity) {
        long productId = product(1_000, 1000);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, quantity)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] quantity={0}")
    @CsvSource({"1", "1000"})
    @DisplayName("R3.2 quantity 경계값 1 과 1,000 은 허용")
    void r3_2_quantityBoundaries_return201(int quantity) {
        long productId = product(1_000, 1000);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, quantity)));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(reservedOf(productId)).isEqualTo(quantity);
    }

    @Test
    @DisplayName("R3.2 quantity 가 없으면 400")
    void r3_2_missingQuantity_returns400() {
        long productId = product(1_000, 10);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"),
                Map.of("items", List.of(Map.of("productId", productId))));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 productId 가 없으면 400")
    void r3_2_missingProductId_returns400() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"),
                Map.of("items", List.of(Map.of("quantity", 1))));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 같은 productId 가 중복되면 400 이고 재고는 예약되지 않는다")
    void r3_2_duplicateProductId_returns400() {
        long productId = product(1_000, 10);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1), item(productId, 2)));

        assertProblem(r, 400, "VALIDATION_ERROR");
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R3.2 couponCode 는 생략할 수 있고 null 이어도 된다")
    void r3_2_couponCodeOptional() {
        long productId = product(1_000, 10);
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("items", List.of(item(productId, 1)));
        explicitNull.put("couponCode", null);

        ResponseEntity<String> omitted = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1)));
        ResponseEntity<String> nullCode = createOrder(uid("u"), uid("k"), explicitNull);

        assertThat(statusOf(omitted)).isEqualTo(201);
        assertThat(statusOf(nullCode)).isEqualTo(201);
        assertThat(json(nullCode).get("couponCode").isNull()).isTrue();
    }

    @Test
    @DisplayName("R3.2 quantity 가 소수이면 400")
    void r3_2_fractionalQuantity_returns400() {
        long productId = product(1_000, 10);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"),
                "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1.5}]}");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R3.3

    @Test
    @DisplayName("R3.3 없는 상품이면 404 PRODUCT_NOT_FOUND")
    void r3_3_unknownProduct_returns404() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(987_654_321L, 1)));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.3 항목 중 하나라도 없는 상품이면 404 이고 다른 상품은 예약되지 않는다")
    void r3_3_oneUnknownProductAmongMany_returns404_andReservesNothing() {
        long good = product(1_000, 10);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(good, 1), item(987_654_321L, 1)));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
        assertThat(reservedOf(good)).isZero();
    }

    @Test
    @DisplayName("R3.3 없는 쿠폰이면 404 COUPON_NOT_FOUND 이고 재고는 예약되지 않는다")
    void r3_3_unknownCoupon_returns404() {
        long productId = product(1_000, 10);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody("NOSUCHCOUPON1", item(productId, 1)));

        assertProblem(r, 404, "COUPON_NOT_FOUND");
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 available 보다 많이 주문하면 409 INSUFFICIENT_STOCK")
    void r3_3_quantityAboveAvailable_returns409() {
        long productId = product(1_000, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 6)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 available 과 정확히 같은 수량은 주문할 수 있다 (경계값)")
    void r3_3_quantityEqualsAvailable_returns201() {
        long productId = product(1_000, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 5)));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(availableOf(productId)).isZero();
    }

    @Test
    @DisplayName("R3.3 이미 예약된 수량은 available 에서 빠진다 (stock 이 충분해도 reserved 때문에 409)")
    void r3_3_reservedQuantityReducesAvailable() {
        long productId = product(1_000, 5);
        orderOk(uid("u"), orderBody(null, item(productId, 4)));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 2)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R3.3 stock 이 0 인 상품은 주문할 수 없다")
    void r3_3_zeroStock_returns409() {
        long productId = product(1_000, 0);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(productId, 1)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    // ---------------------------------------------------------------- R3.4

    @Test
    @DisplayName("R3.4 생성하면 각 상품의 reserved 가 주문 수량만큼 늘고 쿠폰 usedCount 가 1 늘어난다")
    void r3_4_success_incrementsReservedAndUsedCount() {
        long p1 = product(1_000, 10);
        long p2 = product(2_000, 10);
        String code = uniqueCode("ATOM");
        createCoupon(code, "FIXED", 100, 0, null, 3);

        orderOk(uid("u"), orderBody(code, item(p1, 3), item(p2, 4)));

        assertThat(reservedOf(p1)).isEqualTo(3);
        assertThat(reservedOf(p2)).isEqualTo(4);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R3.4 두 번째 상품 재고가 부족하면 첫 상품의 reserved 도 원복된다 (낮은 id 가 먼저 예약되는 순서)")
    void r3_4_secondProductInsufficient_rollsBackFirst() {
        long p1 = product(1_000, 10);
        long p2 = product(1_000, 1);
        String user = uid("u");

        ResponseEntity<String> r = createOrder(user, uid("k"), orderBody(null, item(p1, 3), item(p2, 2)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
    }

    @Test
    @DisplayName("R3.4 요청 순서를 바꿔도(부족한 상품이 먼저) 어느 상품도 예약되지 않는다")
    void r3_4_insufficientFirstInRequest_reservesNothing() {
        long p1 = product(1_000, 10);
        long p2 = product(1_000, 1);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(p2, 2), item(p1, 3)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
    }

    @Test
    @DisplayName("R3.4 실패한 요청은 주문 행을 남기지 않는다")
    void r3_4_failedRequest_leavesNoOrderRow() {
        long p1 = product(1_000, 1);
        String user = uid("u");

        createOrder(user, uid("k"), orderBody(null, item(p1, 5)));

        JsonNode page = json(get("/api/orders?userId=" + user));
        assertThat(page.get("content")).isEmpty();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 소진되어 실패하면 앞서 잡은 reserved 가 원복되고 usedCount 는 그대로다")
    void r3_4_couponExhausted_rollsBackReservation() {
        long productId = product(1_000, 10);
        String code = uniqueCode("ATEX");
        createCoupon(code, "FIXED", 100, 0, null, 1);
        orderOk(uid("u"), orderBody(code, item(productId, 2)));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 3)));

        assertProblem(r, 409, "COUPON_EXHAUSTED");
        assertThat(reservedOf(productId)).isEqualTo(2);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R3.4 쿠폰 적용 불가(같은 사용자 사용 중)로 실패하면 reserved 가 원복된다")
    void r3_4_couponNotApplicable_rollsBackReservation() {
        long productId = product(1_000, 10);
        String code = uniqueCode("ATNA");
        createCoupon(code, "FIXED", 100, 0, null, 5);
        String user = uid("u");
        orderOk(user, orderBody(code, item(productId, 2)));

        ResponseEntity<String> r = createOrder(user, uid("k"), orderBody(code, item(productId, 3)));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(reservedOf(productId)).isEqualTo(2);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R3.4 최소 주문 금액 미달로 실패하면 다른 상품 포함 reserved 가 전부 원복된다")
    void r3_4_minOrderNotMet_rollsBackAllProducts() {
        long p1 = product(1_000, 10);
        long p2 = product(1_000, 10);
        String code = uniqueCode("ATMN");
        createCoupon(code, "FIXED", 100, 1_000_000, null, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(p1, 1), item(p2, 1)));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(usedCountOf(code)).isZero();
    }

    // ---------------------------------------------------------------- R3.5

    @Test
    @DisplayName("R3.5 조회 응답은 {id,userId,status,items[],couponCode,subtotal,discount,totalPrice,createdAt,expiresAt,paidAt} 형태이다")
    void r3_5_get_hasExpectedShape() {
        long productId = product(2_500, 10);
        JsonNode created = orderOk(uid("u"), orderBody(null, item(productId, 3)));

        ResponseEntity<String> r = getOrder(created.get("id").asLong());

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode o = json(r);
        assertThat(o.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(o.get("items").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
        assertThat(o).isEqualTo(created);
    }

    @Test
    @DisplayName("R3.5 쿠폰·결제 전에는 couponCode 와 paidAt 이 null 로 직렬화된다")
    void r3_5_couponCodeAndPaidAt_areNullWhenAbsent() {
        long productId = product(2_500, 10);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));

        assertThat(o.has("couponCode")).isTrue();
        assertThat(o.get("couponCode").isNull()).isTrue();
        assertThat(o.has("paidAt")).isTrue();
        assertThat(o.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R3.5 쿠폰을 쓴 주문은 couponCode 를 돌려준다")
    void r3_5_couponCode_isReturnedWhenUsed() {
        long productId = product(2_500, 10);
        String code = uniqueCode("SHOW");
        createCoupon(code, "FIXED", 100, 0, null, 3);

        JsonNode o = orderOk(uid("u"), orderBody(code, item(productId, 1)));

        assertThat(o.get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R3.5 items 는 요청 순서대로이고 unitPrice 는 주문 시점 상품 가격이다")
    void r3_5_items_keepRequestOrder_withUnitPrice() {
        long cheap = product(1_000, 10);
        long pricey = product(7_000, 10);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(pricey, 2), item(cheap, 5)));

        assertThat(o.get("items").get(0).get("productId").asLong()).isEqualTo(pricey);
        assertThat(o.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(7_000L);
        assertThat(o.get("items").get(1).get("productId").asLong()).isEqualTo(cheap);
        assertThat(o.get("items").get(1).get("unitPrice").asLong()).isEqualTo(1_000L);
        assertThat(o.get("subtotal").asLong()).isEqualTo(19_000L);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(19_000L);
    }

    @Test
    @DisplayName("R3.5 주문 후 상품 가격이 바뀌어도 unitPrice 는 주문 시점 값이다 (스냅샷)")
    void r3_5_unitPrice_isSnapshot() {
        long productId = product(3_000, 10);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 2)));

        jdbc.update("update products set price = 9999 where id = ?", productId);

        JsonNode o = json(getOrder(orderId));
        assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(3_000L);
        assertThat(o.get("subtotal").asLong()).isEqualTo(6_000L);
    }

    @Test
    @DisplayName("R3.5 expiresAt 은 createdAt + 기본 TTL(15분) 이다")
    void r3_5_expiresAt_isCreatedAtPlusDefaultTtl() {
        long productId = product(1_000, 10);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));

        Instant createdAt = Instant.parse(o.get("createdAt").asText());
        Instant expiresAt = Instant.parse(o.get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("R3.5 createdAt 은 요청 처리 시각이다")
    void r3_5_createdAt_isNow() {
        long productId = product(1_000, 10);
        Instant before = Instant.now();

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));

        Instant after = Instant.now();
        Instant createdAt = Instant.parse(o.get("createdAt").asText());
        assertThat(createdAt).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("R3.5 없는 주문은 404 ORDER_NOT_FOUND")
    void r3_5_get_unknownOrder_returns404() {
        assertProblem(getOrder(987_654_321L), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.5 주문 id 가 숫자가 아니면 400")
    void r3_5_get_nonNumericId_returns400() {
        assertProblem(get("/api/orders/abc"), 400, "VALIDATION_ERROR");
    }
}
