package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

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

/** R3(주문 생성·조회) 및 R2.4~R2.6(쿠폰 적용) */
class OrderCreateTest extends IntegrationTestBase {

    // ---- R3.1 / R3.5 -------------------------------------------------------------------------

    @Test
    @DisplayName("R3.1·R3.5 생성하면 201 + Location + PENDING_PAYMENT 본문, 가격은 주문 시점 가격")
    void createOrder() {
        long p1 = product(1000, 10);
        long p2 = product(2500, 10);

        Res r = createOrder("alice", "key-" + uniq(), items(p1, 2, p2, 1), null);

        assertThat(r.status()).isEqualTo(201);
        JsonNode o = r.json();
        assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.get("userId").asText()).isEqualTo("alice");
        assertThat(o.get("items")).hasSize(2);
        assertThat(o.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
        assertThat(o.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
        assertThat(o.get("items").get(1).get("unitPrice").asLong()).isEqualTo(2500);
        assertThat(o.get("couponCode").isNull()).isTrue();
        assertThat(o.get("subtotal").asLong()).isEqualTo(4500);
        assertThat(o.get("discount").asLong()).isZero();
        assertThat(o.get("totalPrice").asLong()).isEqualTo(4500);
        assertThat(o.get("paidAt").isNull()).isTrue();
        assertThat(r.header("Location")).endsWith("/api/orders/" + o.get("id").asLong());
    }

    @Test
    @DisplayName("R3.5 조회: 생성 응답과 같은 형태, expiresAt = createdAt + ORDER_PAYMENT_TTL(기본 15분)")
    void getOrder() {
        long p = product(1000, 10);
        JsonNode created = order(p, 1);

        Res r = get("/api/orders/" + created.get("id").asLong());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json()).isEqualTo(created);
        Instant createdAt = OffsetDateTime.parse(created.get("createdAt").asText()).toInstant();
        Instant expiresAt = OffsetDateTime.parse(created.get("expiresAt").asText()).toInstant();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
        assertThat(createdAt).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(30));
    }

    @Test
    @DisplayName("R3.5 없는 주문은 404 ORDER_NOT_FOUND")
    void getMissingOrder() {
        Res r = get("/api/orders/999999999");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("R3.5 unitPrice는 주문 시점 가격을 유지한다")
    void unitPriceIsSnapshot() {
        long p = product(1000, 10);
        JsonNode o = order(p, 2);
        assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
        // 상품 가격 수정 API는 없으므로, 주문 본문에 가격이 저장돼 있음을 재조회로 확인한다.
        assertThat(orderOf(o.get("id").asLong()).get("items").get(0).get("unitPrice").asLong()).isEqualTo(1000);
    }

    // ---- R3.2 --------------------------------------------------------------------------------

    @Test
    @DisplayName("R3.2 헤더 누락·위반은 400")
    void headerValidation() {
        long p = product(1000, 10);
        Map<String, Object> body = Map.of("items", items(p, 1));

        assertThat(post("/api/orders", body, "Idempotency-Key", "k-" + uniq()).status()).isEqualTo(400);
        assertThat(post("/api/orders", body, "X-User-Id", "u1").status()).isEqualTo(400);
        assertThat(post("/api/orders", body, "X-User-Id", " ", "Idempotency-Key", "k-" + uniq()).status())
                .isEqualTo(400);
        assertThat(post("/api/orders", body, "X-User-Id", "u".repeat(51), "Idempotency-Key", "k-" + uniq())
                .status()).isEqualTo(400);
        assertThat(post("/api/orders", body, "X-User-Id", "u1", "Idempotency-Key", "k".repeat(65)).status())
                .isEqualTo(400);
        Res r = post("/api/orders", body, "X-User-Id", "u".repeat(50), "Idempotency-Key", "k".repeat(64));
        assertThat(r.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R3.2 items·quantity·중복 productId 검증은 400")
    void bodyValidation() {
        long p = product(1000, 100);
        long q = product(1000, 100);
        List<List<Map<String, Object>>> bad = new ArrayList<>();
        bad.add(List.of());
        bad.add(items(p, 0));
        bad.add(items(p, -1));
        bad.add(items(p, 1001));
        bad.add(items(p, 1, p, 2));
        bad.add(items(p, 1, q, 1, p, 1));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(Map.of("productId", p + i + 1000, "quantity", 1));
        }
        bad.add(tooMany);
        for (List<Map<String, Object>> items : bad) {
            Res r = createOrder("u1", "k-" + uniq(), items, null);
            assertThat(r.status()).as("items=%s", items).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(post("/api/orders", "{}", "X-User-Id", "u1", "Idempotency-Key", "k-" + uniq()).status())
                .isEqualTo(400);
        assertThat(post("/api/orders", Map.of("items", List.of(Map.of("quantity", 1))), "X-User-Id", "u1",
                "Idempotency-Key", "k-" + uniq()).status()).isEqualTo(400);
        assertThat(post("/api/orders", "oops", "X-User-Id", "u1", "Idempotency-Key", "k-" + uniq()).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("R3.2 상품 20개·quantity 1,000 경계는 허용")
    void boundariesAccepted() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(Map.of("productId", product(1000, 1000), "quantity", 1000));
        }
        assertThat(createOrder("u1", "k-" + uniq(), items, null).status()).isEqualTo(201);
    }

    // ---- R3.3 / R3.4 -------------------------------------------------------------------------

    @Test
    @DisplayName("R3.3 없는 상품·쿠폰은 404")
    void notFound() {
        long p = product(1000, 10);

        Res noProduct = createOrder("u1", "k-" + uniq(), items(p, 1, 987654321L, 1), null);
        assertThat(noProduct.status()).isEqualTo(404);
        assertThat(noProduct.code()).isEqualTo("PRODUCT_NOT_FOUND");

        Res noCoupon = createOrder("u1", "k-" + uniq(), items(p, 1), "NO-SUCH-COUPON");
        assertThat(noCoupon.status()).isEqualTo(404);
        assertThat(noCoupon.code()).isEqualTo("COUPON_NOT_FOUND");
        assertThat(productOf(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.3 available이 부족하면 409 INSUFFICIENT_STOCK, 경계(available == quantity)는 성공")
    void insufficientStock() {
        long p = product(1000, 5);
        long q = product(1000, 100);

        Res tooMany = createOrder("u1", "k-" + uniq(), items(q, 1, p, 6), null);
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.code()).isEqualTo("INSUFFICIENT_STOCK");

        assertThat(createOrder("u1", "k-" + uniq(), items(p, 5), null).status()).isEqualTo(201);
        Res soldOut = createOrder("u2", "k-" + uniq(), items(p, 1), null);
        assertThat(soldOut.status()).isEqualTo(409);
        assertThat(soldOut.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("R3.4 한 항목이라도 실패하면 어떤 예약·쿠폰 사용도 남지 않는다(전부 아니면 전무)")
    void allOrNothing() {
        long ok = product(1000, 10);
        long scarce = product(1000, 1);
        String coupon = coupon("FIXED", 100, 0, null, 5);

        Res r = createOrder("u1", "k-" + uniq(), items(ok, 3, scarce, 2), coupon);

        assertThat(r.status()).isEqualTo(409);
        assertThat(productOf(ok).get("reserved").asLong()).isZero();
        assertThat(productOf(scarce).get("reserved").asLong()).isZero();
        assertThat(couponOf(coupon).get("usedCount").asLong()).isZero();
    }

    @Test
    @DisplayName("R3.4 생성하면 reserved가 수량만큼, 쿠폰 usedCount가 1 늘어난다")
    void reservesAndUsesCoupon() {
        long p = product(1000, 10);
        long q = product(2000, 10);
        String coupon = coupon("FIXED", 100, 0, null, 5);

        assertThat(createOrder("u1", "k-" + uniq(), items(p, 3, q, 4), coupon).status()).isEqualTo(201);

        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(3);
        assertThat(productOf(p).get("available").asLong()).isEqualTo(7);
        assertThat(productOf(q).get("reserved").asLong()).isEqualTo(4);
        assertThat(couponOf(coupon).get("usedCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("C1 합계가 int 범위를 넘는 주문도 정확히 계산한다")
    void totalsBeyondIntRange() {
        long a = product(10_000_000, 1000);
        long b = product(10_000_000, 1000);
        long c = product(9_999_999, 1000);

        Res r = createOrder("u1", "k-" + uniq(), items(a, 1000, b, 1000, c, 1000), null);

        assertThat(r.status()).isEqualTo(201);
        long expected = 10_000_000L * 1000 * 2 + 9_999_999L * 1000;
        assertThat(expected).isGreaterThan(Integer.MAX_VALUE);
        assertThat(r.json().get("subtotal").asLong()).isEqualTo(expected);
        assertThat(r.json().get("totalPrice").asLong()).isEqualTo(expected);
    }

    // ---- R2.4 할인 계산 ----------------------------------------------------------------------

    private JsonNode orderWithCoupon(long price, int qty, String coupon) {
        long p = product(price, 1000);
        return order(items(p, qty), coupon);
    }

    @Test
    @DisplayName("R2.4 FIXED: value만큼 할인, subtotal을 넘지 못한다")
    void fixedDiscount() {
        JsonNode o = orderWithCoupon(5000, 2, coupon("FIXED", 3000, 0, null, 10));
        assertThat(o.get("subtotal").asLong()).isEqualTo(10000);
        assertThat(o.get("discount").asLong()).isEqualTo(3000);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(7000);
        assertThat(o.get("couponCode").asText()).isNotBlank();

        JsonNode capped = orderWithCoupon(1000, 1, coupon("FIXED", 5000, 0, null, 10));
        assertThat(capped.get("discount").asLong()).isEqualTo(1000);
        assertThat(capped.get("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.4 RATE: floor(subtotal × value / 100), maxDiscountAmount로 상한")
    void rateDiscount() {
        JsonNode floor = orderWithCoupon(999, 1, coupon("RATE", 15, 0, null, 10));
        assertThat(floor.get("discount").asLong()).isEqualTo(149); // 149.85 → 149
        assertThat(floor.get("totalPrice").asLong()).isEqualTo(850);

        JsonNode capped = orderWithCoupon(10000, 1, coupon("RATE", 50, 0, 1200L, 10));
        assertThat(capped.get("discount").asLong()).isEqualTo(1200);

        JsonNode full = orderWithCoupon(10000, 1, coupon("RATE", 100, 0, null, 10));
        assertThat(full.get("discount").asLong()).isEqualTo(10000);
        assertThat(full.get("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.4 큰 금액에서도 RATE 계산이 넘치지 않는다")
    void rateDiscountOnLargeSubtotal() {
        long p = product(10_000_000, 1000);
        JsonNode o = order(items(p, 1000), coupon("RATE", 33, 0, null, 10));
        assertThat(o.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.get("discount").asLong()).isEqualTo(3_300_000_000L);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(6_700_000_000L);
    }

    // ---- R2.5 쿠폰 사용 조건 -----------------------------------------------------------------

    private String couponWithWindow(OffsetDateTime from, OffsetDateTime until, long minOrder) {
        String code = couponCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("type", "FIXED");
        body.put("value", 100);
        body.put("minOrderAmount", minOrder);
        body.put("totalQuantity", 5);
        body.put("validFrom", from.toString());
        body.put("validUntil", until.toString());
        assertThat(post("/api/coupons", body).status()).isEqualTo(201);
        return code;
    }

    @Test
    @DisplayName("R2.5 유효기간 밖이면 409 COUPON_NOT_APPLICABLE")
    void couponOutsideWindow() {
        long p = product(1000, 100);
        OffsetDateTime now = OffsetDateTime.now();
        String notYet = couponWithWindow(now.plusHours(1), now.plusHours(2), 0);
        String expired = couponWithWindow(now.minusHours(2), now.minusHours(1), 0);

        for (String code : List.of(notYet, expired)) {
            Res r = createOrder("u1", "k-" + uniq(), items(p, 1), code);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE");
        }
        assertThat(productOf(p).get("reserved").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.5 validFrom은 포함, validUntil은 미포함")
    void couponWindowEdges() {
        long p = product(1000, 100);
        // validUntil이 곧 지나가도록 짧게 잡고, 지난 뒤에는 쓸 수 없음을 확인한다.
        OffsetDateTime now = OffsetDateTime.now();
        String code = couponWithWindow(now.minusHours(1), now.plusSeconds(2), 0);
        assertThat(createOrder("u1", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
        sleep(2500);
        Res r = createOrder("u2", "k-" + uniq(), items(p, 1), code);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount이면 409, 같으면 사용 가능")
    void couponMinOrderAmount() {
        long p = product(1000, 100);
        OffsetDateTime now = OffsetDateTime.now();
        String code = couponWithWindow(now.minusDays(1), now.plusDays(1), 3000);

        Res low = createOrder("u1", "k-" + uniq(), items(p, 2), code);
        assertThat(low.status()).isEqualTo(409);
        assertThat(low.code()).isEqualTo("COUPON_NOT_APPLICABLE");

        assertThat(createOrder("u1", "k-" + uniq(), items(p, 3), code).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이미 사용 중이면 409, 다른 사용자는 가능")
    void couponOncePerUser() {
        long p = product(1000, 100);
        String code = coupon("FIXED", 100, 0, null, 10);

        assertThat(createOrder("dave", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
        Res again = createOrder("dave", "k-" + uniq(), items(p, 1), code);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(createOrder("erin", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
        assertThat(couponOf(code).get("usedCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity이면 409 COUPON_EXHAUSTED")
    void couponExhausted() {
        long p = product(1000, 100);
        String code = coupon("FIXED", 100, 0, null, 2);
        assertThat(createOrder("u1", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
        assertThat(createOrder("u2", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);

        Res r = createOrder("u3", "k-" + uniq(), items(p, 1), code);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_EXHAUSTED");
        assertThat(couponOf(code).get("usedCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("C3 같은 단계의 409는 재고 → 쿠폰 순")
    void stockConflictBeatsCouponConflict() {
        long p = product(1000, 1);
        String exhausted = coupon("FIXED", 100, 0, null, 1);
        assertThat(createOrder("u1", "k-" + uniq(), items(product(1000, 10), 1), exhausted).status())
                .isEqualTo(201);

        Res r = createOrder("u2", "k-" + uniq(), items(p, 5), exhausted);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 404는 409보다 먼저: 쿠폰이 없으면 재고가 부족해도 404")
    void notFoundBeatsConflict() {
        long p = product(1000, 1);
        Res r = createOrder("u1", "k-" + uniq(), items(p, 5), "MISSING1");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 400은 404보다 먼저: 검증 오류가 있으면 없는 상품이어도 400")
    void validationBeatsNotFound() {
        Res r = createOrder("u1", "k-" + uniq(), items(987654321L, 0), null);
        assertThat(r.status()).isEqualTo(400);
    }

    // ---- R2.6 사용 복원 ----------------------------------------------------------------------

    @Test
    @DisplayName("R2.6 취소되면 예약·쿠폰 사용이 복원되고 같은 사용자가 다시 쓸 수 있다")
    void couponRestoredOnCancel() {
        long p = product(1000, 10);
        String code = coupon("FIXED", 100, 0, null, 1);
        long id = createOrder("frank", "k-" + uniq(), items(p, 2), code).json().get("id").asLong();
        assertThat(couponOf(code).get("usedCount").asLong()).isEqualTo(1);
        assertThat(createOrder("gina", "k-" + uniq(), items(p, 1), code).code()).isEqualTo("COUPON_EXHAUSTED");

        assertThat(post("/api/orders/" + id + "/cancel", null).status()).isEqualTo(200);

        assertThat(couponOf(code).get("usedCount").asLong()).isZero();
        assertThat(productOf(p).get("reserved").asLong()).isZero();
        assertThat(createOrder("frank", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 결제 거절(PAYMENT_FAILED)에서도 쿠폰 사용이 복원된다")
    void couponRestoredOnDecline() {
        long p = product(1000, 10);
        String code = coupon("FIXED", 100, 0, null, 3);
        long id = createOrder("hank", "k-" + uniq(), items(p, 1), code).json().get("id").asLong();

        assertThat(pay(id, FakeGateway.DECLINE_TOKEN, "k-" + uniq()).status()).isEqualTo(402);

        assertThat(couponOf(code).get("usedCount").asLong()).isZero();
        assertThat(createOrder("hank", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 결제 후에도 쿠폰은 사용 중으로 남고, 환불(REFUNDED)되면 복원된다")
    void couponHeldWhilePaidRestoredOnRefund() {
        long p = product(1000, 10);
        String code = coupon("FIXED", 100, 0, null, 3);
        long id = createOrder("iris", "k-" + uniq(), items(p, 1), code).json().get("id").asLong();
        assertThat(pay(id).status()).isEqualTo(200);
        assertThat(couponOf(code).get("usedCount").asLong()).isEqualTo(1);
        assertThat(createOrder("iris", "k-" + uniq(), items(p, 1), code).code()).isEqualTo("COUPON_NOT_APPLICABLE");

        assertThat(post("/api/orders/" + id + "/cancel", null).status()).isEqualTo(200);

        assertThat(couponOf(code).get("usedCount").asLong()).isZero();
        assertThat(createOrder("iris", "k-" + uniq(), items(p, 1), code).status()).isEqualTo(201);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
