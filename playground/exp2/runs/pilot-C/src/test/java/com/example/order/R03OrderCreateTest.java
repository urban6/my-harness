package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R3 주문 생성·조회")
class R03OrderCreateTest extends AbstractIntegrationTest {

    private ApiResponse createWith(Map<String, String> headers, Object body) {
        return post("/api/orders", body, headers);
    }

    private void assertNothingChanged(long productId, int reserved) {
        assertThat(reservedOf(productId)).isEqualTo(reserved);
    }

    // ------------------------------------------------------------- R3.1 / R3.5

    @Test
    @DisplayName("R3.1 주문 생성 -> 201, Location, PENDING_PAYMENT, R3.5 형태")
    void create_returns201WithFullShape() {
        long p1 = newProduct(1_500, 10);
        long p2 = newProduct(250, 10);
        String user = uniqueUser();

        ApiResponse r = createOrder(user, uniqueKey(), null, p1, 2, p2, 4);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/orders/" + r.id());
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "status", "items",
                "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
        assertThat(r.json("userId").asText()).isEqualTo(user);
        assertThat(r.json("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(r.json("couponCode").isNull()).isTrue();
        assertThat(r.json("paidAt").isNull()).isTrue();
        assertThat(r.json("subtotal").asLong()).isEqualTo(4_000);
        assertThat(r.json("discount").asLong()).isZero();
        assertThat(r.json("totalPrice").asLong()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("R3.5 items 는 요청 순서 그대로, productId/quantity/unitPrice 포함")
    void create_itemsInRequestOrder() {
        long p1 = newProduct(1_500, 10);
        long p2 = newProduct(250, 10);
        long p3 = newProduct(9_000, 10);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p3, 1, p1, 2, p2, 3);

        var items = items(r.json("items"));
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("productId").asLong()).isEqualTo(p3);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("unitPrice").asLong()).isEqualTo(9_000);
        assertThat(items.get(1).get("productId").asLong()).isEqualTo(p1);
        assertThat(items.get(1).get("unitPrice").asLong()).isEqualTo(1_500);
        assertThat(items.get(2).get("productId").asLong()).isEqualTo(p2);
        assertThat(items.get(2).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("R3.4 생성 시 각 상품 reserved 가 주문 수량만큼 증가, 쿠폰 usedCount 1 증가")
    void create_reservesStockAndCountsCoupon() {
        long p1 = newProduct(1_000, 10);
        long p2 = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 5);

        newOrder(uniqueUser(), coupon, p1, 3, p2, 5);

        assertThat(reservedOf(p1)).isEqualTo(3);
        assertThat(reservedOf(p2)).isEqualTo(5);
        assertThat(getProduct(p1).json("stock").asInt()).isEqualTo(10);
        assertThat(usedCountOf(coupon)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.5 GET 은 생성 응답과 같은 순간·같은 값을 돌려준다")
    void get_matchesCreateResponse() {
        long p = newProduct(1_000, 10);
        ApiResponse created = newOrder(uniqueUser(), null, p, 2);

        ApiResponse r = getOrder(created.id());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("id").asLong()).isEqualTo(created.id());
        assertThat(r.json("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(r.json("subtotal").asLong()).isEqualTo(2_000);
        assertThat(instant(r.json("createdAt"))).isEqualTo(instant(created.json("createdAt")));
        assertThat(instant(r.json("expiresAt"))).isEqualTo(instant(created.json("expiresAt")));
        assertThat(r.json("items")).isEqualTo(created.json("items"));
    }

    @Test
    @DisplayName("R3.5 expiresAt = createdAt + ORDER_PAYMENT_TTL(기본 PT15M)")
    void create_expiresAtIsCreatedAtPlusTtl() {
        long p = newProduct(1_000, 10);
        Instant before = Instant.now();

        ApiResponse r = newOrder(uniqueUser(), null, p, 1);

        Instant createdAt = instant(r.json("createdAt"));
        assertThat(Duration.between(createdAt, instant(r.json("expiresAt")))).isEqualTo(Duration.ofMinutes(15));
        assertThat(createdAt).isBetween(before.minusSeconds(2), Instant.now().plusSeconds(2));
    }

    @Test
    @DisplayName("R3.5 unitPrice 는 주문 시점 가격의 스냅샷이다 (이후 상품 가격이 바뀌어도 불변)")
    void unitPrice_isSnapshot() {
        long p = newProduct(1_000, 10);
        ApiResponse created = newOrder(uniqueUser(), null, p, 2);

        jdbc.update("update products set price = 5000 where id = ?", p);

        ApiResponse r = getOrder(created.id());
        assertThat(r.json("items").get(0).get("unitPrice").asLong()).isEqualTo(1_000);
        assertThat(r.json("subtotal").asLong()).isEqualTo(2_000);
        assertThat(newOrder(uniqueUser(), null, p, 1).json("items").get(0).get("unitPrice").asLong()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("R3.5 couponCode 를 쓴 주문은 couponCode 를 돌려준다")
    void create_withCoupon_returnsCouponCode() {
        long p = newProduct(1_000, 10);
        String coupon = newCoupon("FIXED", 100, null, null, 5);

        ApiResponse r = newOrder(uniqueUser(), coupon, p, 1);

        assertThat(r.json("couponCode").asText()).isEqualTo(coupon);
        assertThat(r.json("discount").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("R3.5 없는 주문 -> 404 ORDER_NOT_FOUND")
    void get_unknown_404() {
        ApiResponse r = getOrder(Long.MAX_VALUE - 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.5 숫자가 아닌 주문 id -> 400")
    void get_nonNumericId_400() {
        assertThat(get("/api/orders/abc").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------- R3.1/R3.2 headers

    @Test
    @DisplayName("R3.1 X-User-Id 누락 -> 400")
    void create_missingUserId_400() {
        long p = newProduct(1_000, 5);

        ApiResponse r = createWith(headers("Idempotency-Key", uniqueKey()), orderBody(null, p, 1));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 누락 -> 400")
    void create_missingIdempotencyKey_400() {
        long p = newProduct(1_000, 5);

        ApiResponse r = createWith(headers("X-User-Id", uniqueUser()), orderBody(null, p, 1));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.1 공백만 있는 X-User-Id -> 400")
    void create_blankUserId_400() {
        long p = newProduct(1_000, 5);

        ApiResponse r = createWith(headers("X-User-Id", "   ", "Idempotency-Key", uniqueKey()), orderBody(null, p, 1));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 공백만 있는 Idempotency-Key -> 400")
    void create_blankIdempotencyKey_400() {
        long p = newProduct(1_000, 5);

        ApiResponse r = createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", "   "),
                orderBody(null, p, 1));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 X-User-Id 50자는 허용, 51자는 400")
    void create_userIdLengthBoundary() {
        long p = newProduct(1_000, 50);

        ApiResponse ok = createOrder("u".repeat(50), uniqueKey(), null, p, 1);
        ApiResponse tooLong = createOrder("u".repeat(51), uniqueKey(), null, p, 1);

        assertThat(ok.status()).isEqualTo(201);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.1 Idempotency-Key 64자는 허용, 65자는 400")
    void create_idempotencyKeyLengthBoundary() {
        long p = newProduct(1_000, 50);

        ApiResponse ok = createOrder(uniqueUser(), "k".repeat(32) + java.util.UUID.randomUUID().toString().replace("-", ""), null, p, 1);
        ApiResponse tooLong = createOrder(uniqueUser(), "k".repeat(65), null, p, 1);

        assertThat(ok.status()).isEqualTo(201);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(reservedOf(p)).isEqualTo(1);
    }

    // ------------------------------------------------------------- R3.2 body

    @Test
    @DisplayName("R3.2 items 가 비어 있으면 400")
    void create_emptyItems_400() {
        ApiResponse r = createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()),
                obj().set("items", JSON.createArrayNode()));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R3.2 items 누락/null -> 400")
    void create_missingItems_400() {
        Map<String, String> h = headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey());
        assertThat(createWith(h, obj()).status()).isEqualTo(400);
        assertThat(createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()),
                "{\"items\":null}").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R3.2 items 20개는 허용")
    void create_twentyItems_ok() {
        ObjectNode body = obj();
        ArrayNode items = body.putArray("items");
        for (int i = 0; i < 20; i++) {
            items.addObject().put("productId", newProduct(100, 5)).put("quantity", 1);
        }

        ApiResponse r = createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()), body);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("items")).hasSize(20);
        assertThat(r.json("subtotal").asLong()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("R3.2 items 21개는 400, 어떤 상품도 예약되지 않는다")
    void create_twentyOneItems_400() {
        ObjectNode body = obj();
        ArrayNode items = body.putArray("items");
        long first = 0;
        for (int i = 0; i < 21; i++) {
            long id = newProduct(100, 5);
            if (i == 0) first = id;
            items.addObject().put("productId", id).put("quantity", 1);
        }

        ApiResponse r = createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()), body);

        assertThat(r.status()).isEqualTo(400);
        assertNothingChanged(first, 0);
    }

    @ParameterizedTest(name = "R3.2 quantity {0} -> 400")
    @ValueSource(ints = {0, -1, 1001})
    void create_quantityOutOfRange_400(int qty) {
        long p = newProduct(100, 5_000);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, qty);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.2 quantity 1 과 1000 은 허용")
    void create_quantityBoundaries_ok() {
        long p = newProduct(100, 5_000);

        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1).status()).isEqualTo(201);
        assertThat(createOrder(uniqueUser(), uniqueKey(), null, p, 1_000).status()).isEqualTo(201);
        assertThat(reservedOf(p)).isEqualTo(1_001);
    }

    @Test
    @DisplayName("R3.2 같은 productId 중복 -> 400, 예약 없음")
    void create_duplicateProductId_400() {
        long p = newProduct(100, 50);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, 1, p, 2);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.2 productId / quantity 누락 및 item null -> 400")
    void create_itemFieldsMissing_400() {
        Map<String, String> h = headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey());
        long p = newProduct(100, 5);
        assertThat(createWith(h, "{\"items\":[{\"quantity\":1}]}").status()).isEqualTo(400);
        assertThat(createWith(h, "{\"items\":[{\"productId\":" + p + "}]}").status()).isEqualTo(400);
        assertThat(createWith(h, "{\"items\":[null]}").status()).isEqualTo(400);
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.2 couponCode 생략/null 은 쿠폰 없음으로 201")
    void create_couponCodeOptional() {
        long p = newProduct(100, 5);
        Map<String, String> h = headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey());
        ApiResponse r1 = createWith(h, "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}");
        ApiResponse r2 = createWith(headers("X-User-Id", uniqueUser(), "Idempotency-Key", uniqueKey()),
                "{\"couponCode\":null,\"items\":[{\"productId\":" + p + ",\"quantity\":1}]}");

        assertThat(r1.status()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(201);
        assertThat(r2.json("couponCode").isNull()).isTrue();
    }

    // ------------------------------------------------------------- R3.3 404 / 409

    @Test
    @DisplayName("R3.3 없는 상품 -> 404 PRODUCT_NOT_FOUND, 다른 상품 예약 없음")
    void create_unknownProduct_404() {
        long p = newProduct(100, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, 1, Long.MAX_VALUE - 1, 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.3 없는 쿠폰 -> 404 COUPON_NOT_FOUND, 상품 예약 없음")
    void create_unknownCoupon_404() {
        long p = newProduct(100, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), "NOSUCH" + System.nanoTime(), p, 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("COUPON_NOT_FOUND");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.3 available 부족 -> 409 INSUFFICIENT_STOCK")
    void create_insufficientStock_409() {
        long p = newProduct(100, 3);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, 4);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertNothingChanged(p, 0);
    }

    @Test
    @DisplayName("R3.3 available 과 정확히 같은 수량은 허용, 그 다음 1개는 409")
    void create_exactAvailable_ok_thenOneMore_409() {
        long p = newProduct(100, 5);
        newOrder(uniqueUser(), null, p, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, 1);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(getProduct(p).json("available").asInt()).isZero();
    }

    @Test
    @DisplayName("R3.3 stock 은 충분해도 reserved 를 뺀 available 이 부족하면 409")
    void create_availableAccountsForReserved() {
        long p = newProduct(100, 5);
        newOrder(uniqueUser(), null, p, 3);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), null, p, 3);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertNothingChanged(p, 3);
    }

    // ------------------------------------------------------------- R3.4 atomicity

    @Test
    @DisplayName("R3.4 두 번째 품목이 재고 부족이면 첫 번째 품목 예약·쿠폰 사용도 반영되지 않는다")
    void create_secondItemInsufficient_nothingApplied() {
        long ok = newProduct(100, 10);
        long low = newProduct(100, 1);
        String coupon = newCoupon("FIXED", 10, null, null, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), coupon, ok, 5, low, 2);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(reservedOf(ok)).isZero();
        assertThat(reservedOf(low)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 적용 불가면 상품 예약이 전혀 반영되지 않는다")
    void create_couponNotApplicable_noReservation() {
        long p1 = newProduct(100, 10);
        long p2 = newProduct(100, 10);
        String coupon = newCoupon("FIXED", 10, 1_000_000L, null, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), coupon, p1, 2, p2, 2);

        assertThat(r.status()).isEqualTo(409);
        assertThat(reservedOf(p1)).isZero();
        assertThat(reservedOf(p2)).isZero();
        assertThat(usedCountOf(coupon)).isZero();
    }

    @Test
    @DisplayName("R3.4 쿠폰이 소진되었으면 상품 예약이 반영되지 않는다")
    void create_couponExhausted_noReservation() {
        String coupon = newCoupon("FIXED", 10, null, null, 1);
        long p = newProduct(100, 10);
        newOrder(uniqueUser(), coupon, p, 1);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), coupon, p, 4);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_EXHAUSTED");
        assertThat(reservedOf(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("R3.4 실패한 주문은 목록에 생기지 않는다")
    void create_failure_leavesNoOrder() {
        long p = newProduct(100, 1);
        String user = uniqueUser();

        createOrder(user, uniqueKey(), null, p, 2);

        ApiResponse list = listOrders("userId=" + user);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json("content")).isEmpty();
    }
}
