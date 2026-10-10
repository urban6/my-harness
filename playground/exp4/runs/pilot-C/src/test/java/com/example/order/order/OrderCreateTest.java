package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R3. 주문 생성·조회. */
class OrderCreateTest extends IntegrationTestBase {

    private ResponseEntity<JsonNode> createWithHeaders(Map<String, Object> body, String... headers) {
        return post("/api/orders", body, headers);
    }

    private ResponseEntity<JsonNode> create(Map<String, Object> body, String user, String key) {
        return post("/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    private Map<String, Object> bodyFor(long productId, int quantity) {
        return map("items", items(productId, quantity));
    }

    // ------------------------------------------------------------------ R3.1 / R3.5 응답

    @Test
    @DisplayName("R3.1 주문을 만들면 201 + Location + PENDING_PAYMENT 본문")
    void r3_1_createReturns201WithLocationAndPendingStatus() {
        long productId = newProduct(15_000, 10);
        String user = uniqueUser();

        ResponseEntity<JsonNode> res = placeOrder(user, uniqueKey(), null, items(productId, 2));

        assertStatus(res, 201);
        long id = res.getBody().get("id").asLong();
        assertThat(res.getHeaders().getLocation()).isNotNull();
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + id);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(res.getBody().get("userId").asText()).isEqualTo(user);
    }

    @Test
    @DisplayName("R3.1 생성 응답 본문은 GET 조회(R3.5)와 같다")
    void r3_1_createBodyEqualsGetBody() {
        long productId = newProduct(15_000, 10);

        JsonNode created = newOrder(productId, 1);

        assertThat(get("/api/orders/" + created.get("id").asLong()).getBody()).isEqualTo(created);
    }

    @Test
    @DisplayName("R3.5 응답은 문서화된 필드를 모두 가지며 couponCode·paidAt 은 null, items 는 요청 순서")
    void r3_5_responseShape() {
        long a = newProduct(15_000, 10);
        long b = newProduct(700, 10);

        JsonNode order = newOrder(uniqueUser(), null, items(b, 3, a, 2));

        List<String> fields = new ArrayList<>();
        order.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("id", "userId", "status", "items", "couponCode", "subtotal",
                "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(order.get("couponCode").isNull()).isTrue();
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("items").get(0).get("productId").asLong()).isEqualTo(b);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(order.get("items").get(0).get("unitPrice").asLong()).isEqualTo(700);
        assertThat(order.get("items").get(1).get("productId").asLong()).isEqualTo(a);
        assertThat(order.get("items").get(1).get("quantity").asInt()).isEqualTo(2);
        assertThat(order.get("items").get(1).get("unitPrice").asLong()).isEqualTo(15_000);
        assertThat(order.get("subtotal").asLong()).isEqualTo(2100 + 30_000);
        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(32_100);
    }

    @Test
    @DisplayName("R3.5/C4 expiresAt = createdAt + 기본 TTL(PT15M)")
    void r3_5_expiresAtIsCreatedAtPlusDefaultTtl() {
        JsonNode order = newOrder(newProduct(1000, 5), 1);

        Duration ttl = Duration.between(time(order, "createdAt"), time(order, "expiresAt"));

        assertThat(ttl).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("R3.5 쿠폰을 쓴 주문은 couponCode 를 돌려준다")
    void r3_5_couponCodeReturned() {
        String code = newCoupon("FIXED", 100, 5);

        JsonNode order = newOrder(uniqueUser(), code, items(newProduct(1000, 5), 1));

        assertThat(order.get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R3.5 unitPrice 는 주문 시점 가격의 스냅샷이다 (이후 상품 가격이 바뀌어도 주문은 그대로)")
    void r3_5_unitPriceIsSnapshotAtOrderTime() {
        long productId = newProduct(1000, 10);
        JsonNode first = newOrder(productId, 2);

        jdbc.update("update products set price = 2000 where id = ?", productId);

        JsonNode reread = order(first.get("id").asLong());
        assertThat(reread.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
        assertThat(reread.get("subtotal").asLong()).isEqualTo(2000);
        JsonNode second = newOrder(productId, 2);
        assertThat(second.get("items").get(0).get("unitPrice").asLong()).isEqualTo(2000);
        assertThat(second.get("subtotal").asLong()).isEqualTo(4000);
    }

    @Test
    @DisplayName("R3.5 없는 주문 조회는 404 ORDER_NOT_FOUND")
    void r3_5_unknownOrderReturns404() {
        assertProblem(get("/api/orders/999999999"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("C1 subtotal 이 int 범위를 넘는 주문(10,000,000 x 1000 x 2종)도 정상 처리된다")
    void c1_subtotalBeyondIntRange() {
        long a = newProduct(10_000_000L, 1000);
        long b = newProduct(10_000_000L, 1000);

        JsonNode order = newOrder(uniqueUser(), null, items(a, 1000, b, 1000));

        assertThat(order.get("subtotal").asLong()).isEqualTo(20_000_000_000L);
        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(20_000_000_000L);
        assertThat(order.get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000_000L);
        assertStock(a, 1000, 1000);
    }

    @Test
    @DisplayName("C1 최대 구성(20종 x 수량 1000 x 10,000,000원 = 2천억)도 오버플로 없이 처리된다")
    void c1_maximumOrderSubtotal() {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(item(newProduct(10_000_000L, 1000), 1000));
        }

        JsonNode order = newOrder(uniqueUser(), null, lines);

        assertThat(order.get("subtotal").asLong()).isEqualTo(200_000_000_000L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(200_000_000_000L);
    }

    // ------------------------------------------------------------------ R3.1 / R3.2 헤더·본문 검증

    @Test
    @DisplayName("R3.2 X-User-Id 누락은 400")
    void r3_2_missingUserIdRejected() {
        long p = newProduct(1000, 5);

        assertProblem(createWithHeaders(bodyFor(p, 1), "Idempotency-Key", uniqueKey()), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R3.2 X-User-Id=\"{0}\" (공백) 은 400")
    @ValueSource(strings = {"", " ", "   "})
    void r3_2_blankUserIdRejected(String user) {
        long p = newProduct(1000, 5);

        assertProblem(createWithHeaders(bodyFor(p, 1), "X-User-Id", user, "Idempotency-Key", uniqueKey()), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 X-User-Id 51자는 400, 50자는 201")
    void r3_2_userIdLengthBoundary() {
        long p = newProduct(1000, 5);

        assertProblem(create(bodyFor(p, 1), "u".repeat(51), uniqueKey()), 400, "VALIDATION_ERROR");
        ResponseEntity<JsonNode> ok = create(bodyFor(p, 1), "u".repeat(50), uniqueKey());
        assertStatus(ok, 201);
        assertThat(ok.getBody().get("userId").asText()).isEqualTo("u".repeat(50));
    }

    @Test
    @DisplayName("R3.2 Idempotency-Key 누락은 400")
    void r3_2_missingIdempotencyKeyRejected() {
        long p = newProduct(1000, 5);

        assertProblem(createWithHeaders(bodyFor(p, 1), "X-User-Id", uniqueUser()), 400, "VALIDATION_ERROR");
        assertStock(p, 5, 0);
    }

    @Test
    @DisplayName("R3.2 Idempotency-Key 65자는 400, 64자와 1자는 201")
    void r3_2_idempotencyKeyLengthBoundary() {
        long p = newProduct(1000, 5);

        assertProblem(create(bodyFor(p, 1), uniqueUser(), "k".repeat(65)), 400, "VALIDATION_ERROR");
        assertStatus(create(bodyFor(p, 1), uniqueUser(), uniqueKey().replace("-", "") + "x".repeat(32)), 201);
        assertStatus(create(bodyFor(p, 1), uniqueUser(), "k"), 201);
    }

    @Test
    @DisplayName("R3.2 items 가 비어 있거나 누락·null 이면 400")
    void r3_2_emptyOrMissingItemsRejected() {
        assertProblem(create(map("items", List.of()), uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
        assertProblem(create(map(), uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
        Map<String, Object> nullItems = map("items", null);
        assertProblem(create(nullItems, uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 items 21개는 400, 20개는 201")
    void r3_2_itemsCountBoundary() {
        List<Map<String, Object>> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(item(newProduct(1000, 5), 1));
        }

        assertProblem(create(map("items", twentyOne), uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
        assertStatus(create(map("items", twentyOne.subList(0, 20)), uniqueUser(), uniqueKey()), 201);
        // 400 이었던 21번째 상품은 예약되지 않았다.
        long untouched = twentyOne.get(20).get("productId") instanceof Number n ? n.longValue() : -1;
        assertStock(untouched, 5, 0);
    }

    @ParameterizedTest(name = "R3.2 quantity={0} 은 400")
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1001, Integer.MAX_VALUE})
    void r3_2_quantityOutOfRangeRejected(int quantity) {
        long p = newProduct(1000, 1000);

        assertProblem(create(bodyFor(p, quantity), uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
        assertStock(p, 1000, 0);
    }

    @ParameterizedTest(name = "R3.2 quantity={0} 은 201 (경계)")
    @ValueSource(ints = {1, 1000})
    void r3_2_quantityBoundariesAccepted(int quantity) {
        long p = newProduct(1000, 1000);

        assertStatus(create(bodyFor(p, quantity), uniqueUser(), uniqueKey()), 201);
        assertStock(p, 1000, quantity);
    }

    @Test
    @DisplayName("R3.2 같은 productId 가 중복되면 400, 예약은 생기지 않는다")
    void r3_2_duplicateProductIdRejected() {
        long p = newProduct(1000, 10);

        ResponseEntity<JsonNode> res = create(map("items", items(p, 1, p, 2)), uniqueUser(), uniqueKey());

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertStock(p, 10, 0);
    }

    @Test
    @DisplayName("R3.2 productId·quantity 누락, 항목 null 은 400")
    void r3_2_incompleteItemRejected() {
        long p = newProduct(1000, 10);
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);

        assertProblem(create(map("items", List.of(map("quantity", 1))), uniqueUser(), uniqueKey()), 400,
                "VALIDATION_ERROR");
        assertProblem(create(map("items", List.of(map("productId", p))), uniqueUser(), uniqueKey()), 400,
                "VALIDATION_ERROR");
        assertProblem(create(map("items", withNull), uniqueUser(), uniqueKey()), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 couponCode 는 생략·null 모두 허용")
    void r3_2_couponCodeOptional() {
        long p = newProduct(1000, 10);
        Map<String, Object> explicitNull = bodyFor(p, 1);
        explicitNull.put("couponCode", null);

        assertStatus(create(bodyFor(p, 1), uniqueUser(), uniqueKey()), 201);
        assertStatus(create(explicitNull, uniqueUser(), uniqueKey()), 201);
    }

    @Test
    @DisplayName("R3.2 본문 형식 오류(깨진 JSON, 문자열 quantity, 소수 quantity, 빈 본문)는 400")
    void r3_2_malformedBodyRejected() {
        String[] headers = {"X-User-Id", "u-malformed", "Idempotency-Key", ""};
        for (String body : new String[] {"{\"items\":", "{\"items\":[{\"productId\":1,\"quantity\":\"2\"}]}",
                "{\"items\":[{\"productId\":1,\"quantity\":1.5}]}", "", "[]"}) {
            headers[3] = uniqueKey();
            assertProblem(post("/api/orders", body, headers), 400, "VALIDATION_ERROR");
        }
    }

    // ------------------------------------------------------------------ R3.3 404 / 409

    @Test
    @DisplayName("R3.3 없는 상품은 404 PRODUCT_NOT_FOUND")
    void r3_3_unknownProductReturns404() {
        assertProblem(placeOrder(uniqueUser(), uniqueKey(), null, items(999_999_999L, 1)), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.3 없는 쿠폰은 404 COUPON_NOT_FOUND, 예약은 생기지 않는다")
    void r3_3_unknownCouponReturns404() {
        long p = newProduct(1000, 10);

        assertProblem(placeOrder(uniqueUser(), "NOSUCHCOUPON", p, 1), 404, "COUPON_NOT_FOUND");
        assertStock(p, 10, 0);
    }

    @Test
    @DisplayName("R3.3 available 이 부족하면 409 INSUFFICIENT_STOCK")
    void r3_3_insufficientStockReturns409() {
        long p = newProduct(1000, 5);

        assertProblem(placeOrder(uniqueUser(), null, p, 6), 409, "INSUFFICIENT_STOCK");
        assertStock(p, 5, 0);
    }

    @Test
    @DisplayName("R3.3 available 과 정확히 같은 수량은 성공하고 available 은 0 이 된다")
    void r3_3_quantityEqualToAvailableAccepted() {
        long p = newProduct(1000, 5);

        assertStatus(placeOrder(uniqueUser(), null, p, 5), 201);
        assertStock(p, 5, 5);
        assertProblem(placeOrder(uniqueUser(), null, p, 1), 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R3.3 stock 이 아니라 available(= stock - reserved) 기준으로 판단한다")
    void r3_3_checksAvailableNotStock() {
        long p = newProduct(1000, 5);
        newOrder(p, 3);

        assertProblem(placeOrder(uniqueUser(), null, p, 3), 409, "INSUFFICIENT_STOCK");
        assertStatus(placeOrder(uniqueUser(), null, p, 2), 201);
    }

    @Test
    @DisplayName("R3.3 stock 0 인 상품은 어떤 수량도 주문할 수 없다")
    void r3_3_zeroStockProduct() {
        long p = newProduct(1000, 0);

        assertProblem(placeOrder(uniqueUser(), null, p, 1), 409, "INSUFFICIENT_STOCK");
    }

    // ------------------------------------------------------------------ R3.4 예약·롤백

    @Test
    @DisplayName("R3.4 생성 시 각 상품 reserved 가 주문 수량만큼, 쿠폰 usedCount 가 1 늘어난다")
    void r3_4_reservesStockAndCoupon() {
        long a = newProduct(1000, 10);
        long b = newProduct(2000, 10);
        String code = newCoupon("FIXED", 100, 5);

        newOrder(uniqueUser(), code, items(a, 3, b, 4));

        assertStock(a, 10, 3);
        assertStock(b, 10, 4);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R3.4 두 번째 항목의 재고가 부족하면 전부 롤백된다 (첫 상품 reserved·쿠폰 usedCount 불변, 주문 없음)")
    void r3_4_secondItemInsufficientRollsBackEverything() {
        long a = newProduct(1000, 10);
        long b = newProduct(2000, 2);
        String code = newCoupon("FIXED", 100, 5);
        String user = uniqueUser();

        ResponseEntity<JsonNode> res = placeOrder(user, uniqueKey(), code, items(a, 3, b, 3));

        assertProblem(res, 409, "INSUFFICIENT_STOCK");
        assertStock(a, 10, 0);
        assertStock(b, 2, 0);
        assertUsedCount(code, 0);
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, user)).isZero();
    }

    @Test
    @DisplayName("R3.4 두 번째 항목의 상품이 없으면(404) 첫 상품 reserved·쿠폰 usedCount 는 불변")
    void r3_4_secondItemUnknownRollsBackEverything() {
        long a = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 5);

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), code, items(a, 3, 999_999_999L, 1));

        assertProblem(res, 404, "PRODUCT_NOT_FOUND");
        assertStock(a, 10, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R3.4 쿠폰이 적용 불가(최소금액 미달)면 상품 예약도 전부 롤백된다")
    void r3_4_couponNotApplicableRollsBackReservations() {
        long a = newProduct(1000, 10);
        long b = newProduct(1000, 10);
        Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 100);
        body.put("minOrderAmount", 1_000_000);
        String code = createCoupon(body).get("code").asText();

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), code, items(a, 1, b, 1));

        assertProblem(res, 409, "COUPON_NOT_APPLICABLE");
        assertStock(a, 10, 0);
        assertStock(b, 10, 0);
        assertUsedCount(code, 0);
    }

    @Test
    @DisplayName("R3.4 쿠폰이 소진됐으면 상품 예약도 전부 롤백된다")
    void r3_4_couponExhaustedRollsBackReservations() {
        long a = newProduct(1000, 10);
        String code = newCoupon("FIXED", 100, 1);
        newOrder(uniqueUser(), code, items(a, 1));

        ResponseEntity<JsonNode> res = placeOrder(uniqueUser(), uniqueKey(), code, items(a, 2));

        assertProblem(res, 409, "COUPON_EXHAUSTED");
        assertStock(a, 10, 1);
        assertUsedCount(code, 1);
    }

    @Test
    @DisplayName("R3.4 같은 상품 여러 주문의 예약은 누적된다")
    void r3_4_reservationsAccumulate() {
        long p = newProduct(1000, 10);

        newOrder(p, 2);
        newOrder(p, 3);

        assertStock(p, 10, 5);
    }
}
