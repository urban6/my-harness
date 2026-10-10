package com.example.order.requirements;

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
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/** 공통 규약 C1(금액 long) · C2(시각 ISO-8601 오프셋) · C3(오류 우선순위) · C4(설정 바인딩, 기본값 PT15M 등). */
@DisplayName("C1~C4. 공통 규약")
class CommonConventionsTest extends IntegrationTestBase {

    /** 오프셋(Z 또는 ±hh:mm)이 반드시 포함된 ISO-8601 date-time. */
    private static final Pattern ISO_WITH_OFFSET =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$");

    @Autowired
    private Environment environment;

    // ================================================================ C1 금액

    @Test
    @DisplayName("C1 단일 항목 10,000,000 × 1,000 = 10,000,000,000 (int 초과) 이 정확히 계산·응답된다")
    void c1_singleItem_subtotalExceedsInt() {
        long productId = product(10_000_000, 1000);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1000)));

        assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(10_000_000L);
        assertThat(o.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.get("discount").asLong()).isZero();
        assertThat(o.get("totalPrice").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.get("subtotal").isIntegralNumber()).isTrue();
        assertThat(o.get("subtotal").canConvertToInt()).isFalse();
    }

    @Test
    @DisplayName("C1 최대 주문(20항목 × 1,000개 × 10,000,000원 = 200,000,000,000) 의 금액이 정확하다")
    void c1_maxOrder_subtotalIs200Billion() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item(product(10_000_000, 1000), 1000));
        }

        JsonNode o = orderOk(uid("u"), Map.of("items", items));

        assertThat(o.get("subtotal").asLong()).isEqualTo(200_000_000_000L);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(200_000_000_000L);
        assertThat(json(getOrder(o.get("id").asLong())).get("totalPrice").asLong()).isEqualTo(200_000_000_000L);
    }

    @Test
    @DisplayName("C1 RATE 쿠폰 할인(200,000,000,000 의 15% = 30,000,000,000)이 long 으로 정확히 계산된다")
    void c1_rateDiscount_onHugeSubtotal() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item(product(10_000_000, 1000), 1000));
        }
        String code = uniqueCode("C1RATE");
        createCoupon(code, "RATE", 15, 0, null, 3);

        JsonNode o = orderOk(uid("u"), Map.of("items", items, "couponCode", code));

        assertThat(o.get("subtotal").asLong()).isEqualTo(200_000_000_000L);
        assertThat(o.get("discount").asLong()).isEqualTo(30_000_000_000L);
        assertThat(o.get("totalPrice").asLong()).isEqualTo(170_000_000_000L);
    }

    @Test
    @DisplayName("C1 int 범위를 넘는 FIXED 할인액·maxDiscountAmount·minOrderAmount 가 쿠폰에 그대로 저장·조회된다")
    void c1_couponAmounts_exceedingInt_roundTrip() {
        String code = uniqueCode("C1CPN");

        ResponseEntity<String> created = createCouponResponse(code, "FIXED", 5_000_000_000L, 3_000_000_000L, 4_000_000_000L, 2);

        assertThat(statusOf(created)).isEqualTo(201);
        JsonNode c = json(getCoupon(code));
        assertThat(c.get("value").asLong()).isEqualTo(5_000_000_000L);
        assertThat(c.get("minOrderAmount").asLong()).isEqualTo(3_000_000_000L);
        assertThat(c.get("maxDiscountAmount").asLong()).isEqualTo(4_000_000_000L);
    }

    @Test
    @DisplayName("C1 int 초과 minOrderAmount 와 maxDiscountAmount 가 주문 할인 계산에 정확히 적용된다")
    void c1_hugeMinOrderAndMaxDiscount_applyInOrder() {
        long productId = product(10_000_000, 1000);
        String code = uniqueCode("C1MAX");
        createCoupon(code, "FIXED", 5_000_000_000L, 3_000_000_000L, 4_000_000_000L, 2);

        JsonNode o = orderOk(uid("u"), orderBody(code, item(productId, 1000)));

        assertThat(o.get("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(o.get("discount").asLong()).isEqualTo(4_000_000_000L); // FIXED 5e9 -> max 4e9 로 상한
        assertThat(o.get("totalPrice").asLong()).isEqualTo(6_000_000_000L);
    }

    @Test
    @DisplayName("C1 subtotal 이 int 범위를 넘어도 minOrderAmount 미달 판정이 정확하다 (3,000,000,000 vs 2,000,000,000)")
    void c1_minOrderComparison_beyondInt() {
        long productId = product(10_000_000, 1000);
        String code = uniqueCode("C1MIN");
        createCoupon(code, "FIXED", 1_000, 3_000_000_000L, null, 2);

        ResponseEntity<String> below = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 200))); // 2e9
        ResponseEntity<String> above = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 300))); // 3e9

        assertProblem(below, 409, "COUPON_NOT_APPLICABLE");
        assertThat(statusOf(above)).isEqualTo(201);
    }

    @Test
    @DisplayName("C1 int 초과 totalPrice 는 PG amount 로 정확히 전달된다")
    void c1_pgAmount_exceedsInt() throws Exception {
        long productId = product(10_000_000, 1000);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1000)));

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode sent = objectMapper.readTree(PG.receivedPayments().get(0).body());
        assertThat(sent.get("amount").asLong()).isEqualTo(10_000_000_000L);
        assertThat(json(r).get("totalPrice").asLong()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("C1 목록 응답의 금액도 int 범위를 넘는 값이 정확하다")
    void c1_listResponse_keepsLongAmounts() {
        long productId = product(10_000_000, 1000);
        String user = uid("u");
        orderOk(user, orderBody(null, item(productId, 1000)));

        JsonNode page = json(get("/api/orders?userId=" + user));

        assertThat(page.get("content").get(0).get("subtotal").asLong()).isEqualTo(10_000_000_000L);
    }

    // ================================================================ C2 시각

    private static void assertIsoWithOffset(JsonNode node, String field) {
        assertThat(node.get(field).isTextual()).as("%s 는 문자열", field).isTrue();
        String text = node.get(field).asText();
        assertThat(text).as(field).matches(ISO_WITH_OFFSET);
        assertThat(OffsetDateTime.parse(text)).as(field + " 파싱").isNotNull();
    }

    @Test
    @DisplayName("C2 주문의 createdAt·expiresAt 은 오프셋이 포함된 ISO-8601 문자열이고 paidAt 은 null")
    void c2_order_timestampsHaveOffset() {
        long productId = product(1_000, 5);

        JsonNode o = orderOk(uid("u"), orderBody(null, item(productId, 1)));

        assertIsoWithOffset(o, "createdAt");
        assertIsoWithOffset(o, "expiresAt");
        assertThat(o.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("C2 결제 후 paidAt 도 오프셋이 포함된 ISO-8601 문자열이다")
    void c2_paidAt_hasOffset() {
        long productId = product(1_000, 5);
        long orderId = orderIdOk(uid("u"), orderBody(null, item(productId, 1)));

        JsonNode paid = payOk(orderId);

        assertIsoWithOffset(paid, "paidAt");
        assertIsoWithOffset(paid, "createdAt");
    }

    @Test
    @DisplayName("C2 목록·조회 응답의 시각도 모두 오프셋을 포함한다")
    void c2_listAndGet_timestampsHaveOffset() {
        long productId = product(1_000, 5);
        String user = uid("u");
        JsonNode created = orderOk(user, orderBody(null, item(productId, 1)));

        JsonNode fromList = json(get("/api/orders?userId=" + user)).get("content").get(0);
        JsonNode fromGet = json(getOrder(created.get("id").asLong()));

        assertIsoWithOffset(fromList, "createdAt");
        assertIsoWithOffset(fromList, "expiresAt");
        assertIsoWithOffset(fromGet, "createdAt");
        assertIsoWithOffset(fromGet, "expiresAt");
    }

    @Test
    @DisplayName("C2 쿠폰 validFrom·validUntil 응답은 오프셋이 포함된 ISO-8601 문자열이다")
    void c2_coupon_timestampsHaveOffset() {
        String code = uniqueCode("C2CPN");
        createCoupon(code, "FIXED", 100, 0, null, 1);

        JsonNode c = json(getCoupon(code));

        assertIsoWithOffset(c, "validFrom");
        assertIsoWithOffset(c, "validUntil");
    }

    @Test
    @DisplayName("C2 +09:00 오프셋으로 등록해도 같은 순간으로 저장되고 응답은 오프셋 포함 형식이다")
    void c2_requestWithNonUtcOffset_isAcceptedAndKeepsInstant() {
        String code = uniqueCode("C2OFF");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("type", "FIXED");
        m.put("value", 100);
        m.put("totalQuantity", 1);
        m.put("validFrom", "2030-01-01T09:00:00+09:00");
        m.put("validUntil", "2030-12-31T09:00:00+09:00");

        ResponseEntity<String> r = post("/api/coupons", m);

        assertThat(statusOf(r)).isEqualTo(201);
        JsonNode c = json(getCoupon(code));
        assertIsoWithOffset(c, "validFrom");
        assertThat(OffsetDateTime.parse(c.get("validFrom").asText()).toInstant()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(OffsetDateTime.parse(c.get("validUntil").asText()).toInstant()).isEqualTo(Instant.parse("2030-12-31T00:00:00Z"));
    }

    @Test
    @DisplayName("C2 오프셋이 없는 시각 문자열로 쿠폰을 등록하면 400")
    void c2_requestWithoutOffset_returns400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", uniqueCode("C2NOF"));
        m.put("type", "FIXED");
        m.put("value", 100);
        m.put("totalQuantity", 1);
        m.put("validFrom", "2030-01-01T00:00:00");
        m.put("validUntil", "2030-12-31T00:00:00");

        assertProblem(post("/api/coupons", m), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 날짜만 있는 문자열(오프셋·시간 없음)이면 400")
    void c2_dateOnly_returns400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", uniqueCode("C2DAT"));
        m.put("type", "FIXED");
        m.put("value", 100);
        m.put("totalQuantity", 1);
        m.put("validFrom", "2030-01-01");
        m.put("validUntil", "2030-12-31");

        assertProblem(post("/api/coupons", m), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C2 시각 형식이 아닌 문자열이면 400")
    void c2_garbageTimestamp_returns400() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", uniqueCode("C2BAD"));
        m.put("type", "FIXED");
        m.put("value", 100);
        m.put("totalQuantity", 1);
        m.put("validFrom", "yesterday");
        m.put("validUntil", "2030-12-31T00:00:00Z");

        assertProblem(post("/api/coupons", m), 400, "VALIDATION_ERROR");
    }

    // ================================================================ C3 오류 우선순위

    @Test
    @DisplayName("C3 400(수량 0) 과 404(없는 상품) 가 겹치면 400")
    void c3_400BeatsProduct404() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(987_654_321L, 0)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400(X-User-Id 누락) 과 404(없는 상품) 가 겹치면 400")
    void c3_400MissingHeaderBeatsProduct404() {
        ResponseEntity<String> r = post("/api/orders", orderBody(null, item(987_654_321L, 1)), "Idempotency-Key", uid("k"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400(cardToken 공백) 과 404(없는 주문) 가 겹치면 400")
    void c3_400BeatsOrder404_onPay() {
        ResponseEntity<String> r = pay(987_654_321L, uid("pk"), " ");

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400(Idempotency-Key 누락) 과 404(없는 주문) 가 겹치면 400")
    void c3_400MissingKeyBeatsOrder404_onPay() {
        ResponseEntity<String> r = post("/api/orders/987654321/pay", Map.of("cardToken", "tok"));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 400 은 멱등 422 보다 먼저다 (같은 키에 잘못된 본문을 보내면 422 가 아니라 400)")
    void c3_400BeatsIdempotency422() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        createOrder(user, key, orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = createOrder(user, key, orderBody(null, item(productId, 0)));

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("C3 멱등 422 는 404 보다 먼저다 (같은 키에 없는 상품 본문을 보내면 404 가 아니라 422)")
    void c3_idempotency422BeatsProduct404() {
        long productId = product(1_000, 5);
        String user = uid("u");
        String key = uid("k");
        createOrder(user, key, orderBody(null, item(productId, 1)));

        ResponseEntity<String> r = createOrder(user, key, orderBody(null, item(987_654_321L, 1)));

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제에서 멱등 422 는 404 보다 먼저다 (이미 쓴 키로 없는 주문을 결제하면 404 가 아니라 422)")
    void c3_idempotency422BeatsOrder404_onPay() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        String key = uid("pk");
        pay(orderId, key, "tok");

        ResponseEntity<String> r = pay(987_654_321L, key, "tok");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 결제에서 멱등 422 는 409 INVALID_STATE 보다 먼저다 (이미 PAID 인 주문에 다른 cardToken 으로 같은 키 재사용)")
    void c3_idempotency422BeatsInvalidState409_onPay() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        String key = uid("pk");
        pay(orderId, key, "tok_a");

        ResponseEntity<String> r = pay(orderId, key, "tok_b");

        assertProblem(r, 422, "IDEMPOTENCY_KEY_MISMATCH");
    }

    @Test
    @DisplayName("C3 멱등 재생은 409 INVALID_STATE 보다 먼저다 (이미 PAID 인 주문에 같은 키·같은 요청 → 최초 200)")
    void c3_idempotencyReplayBeatsInvalidState409_onPay() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        String key = uid("pk");
        ResponseEntity<String> first = pay(orderId, key, "tok");

        ResponseEntity<String> replay = pay(orderId, key, "tok");

        assertThat(statusOf(replay)).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("C3 404(없는 쿠폰) 와 409(재고 부족) 가 겹치면 404")
    void c3_404CouponBeatsStock409() {
        long productId = product(1_000, 1);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody("NOSUCHCOUPON7", item(productId, 5)));

        assertProblem(r, 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404(없는 상품) 와 409(다른 상품 재고 부족) 가 겹치면 404")
    void c3_404ProductBeatsStock409() {
        long scarce = product(1_000, 1);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(null, item(scarce, 5), item(987_654_321L, 1)));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 404(없는 상품) 와 404(없는 쿠폰) 가 겹치면 상품 404 가 먼저")
    void c3_productNotFoundBeatsCouponNotFound() {
        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody("NOSUCHCOUPON7", item(987_654_321L, 1)));

        assertProblem(r, 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 409 재고 부족 과 409 쿠폰 소진이 겹치면 INSUFFICIENT_STOCK")
    void c3_stock409BeatsCouponExhausted() {
        long productId = product(1_000, 5);
        String code = uniqueCode("C3EX");
        createCoupon(code, "FIXED", 100, 0, null, 1);
        orderOk(uid("u"), orderBody(code, item(productId, 1)));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 50)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 재고 부족 과 409 쿠폰 적용 불가(최소 금액 미달)가 겹치면 INSUFFICIENT_STOCK")
    void c3_stock409BeatsCouponNotApplicable() {
        long productId = product(1_000, 5);
        String code = uniqueCode("C3NA");
        createCoupon(code, "FIXED", 100, 100_000_000, null, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 50)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 재고 부족 과 409 쿠폰 적용 불가(기간 밖)가 겹쳐도 INSUFFICIENT_STOCK")
    void c3_stock409BeatsCouponExpiredPeriod() {
        long productId = product(1_000, 5);
        String code = uniqueCode("C3PE");
        createCouponWithPeriod(code, "FIXED", 100, 0, null, 5, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 50)));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("C3 409 INVALID_STATE 는 PG 결과보다 먼저다 (PG 가 거절 모드여도 이미 취소된 주문은 409, PG 미호출)")
    void c3_invalidState409BeatsPgDecline402() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        cancel(orderId);
        PG.declinePayments();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.paymentCalls()).isZero();
    }

    @Test
    @DisplayName("C3 409 INVALID_STATE 는 PG 장애(503)보다 먼저다 (PG 가 내려가 있어도 이미 결제된 주문은 409)")
    void c3_invalidState409BeatsPgUnavailable503() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        payOk(orderId);
        PG.stop();

        ResponseEntity<String> r = pay(orderId, uid("pk"), "tok");

        assertProblem(r, 409, "INVALID_STATE");
    }

    @Test
    @DisplayName("C3 404 는 PG 결과보다 먼저다 (PG 가 내려가 있어도 없는 주문은 404)")
    void c3_404BeatsPgUnavailable503() {
        PG.stop();

        assertProblem(pay(987_654_321L, uid("pk"), "tok"), 404, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("C3 409 INVALID_STATE 는 환불 PG 장애(503)보다 먼저다 (SHIPPED 주문 취소는 PG 가 죽어 있어도 409)")
    void c3_invalidState409BeatsRefundUnavailable503() {
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));
        payOk(orderId);
        ship(orderId);
        PG.failRefundsWith5xx();

        ResponseEntity<String> r = cancel(orderId);

        assertProblem(r, 409, "INVALID_STATE");
        assertThat(PG.refundCalls()).isZero();
    }

    // ================================================================ C4 설정 (기본값·바인딩; 환경 변수 실제 주입은 C4EnvironmentVariablesTest)

    @Test
    @DisplayName("C4 PAYMENT_GATEWAY_URL 플레이스홀더가 payment.gateway.url 로 바인딩되어 PG 스텁으로 요청이 나간다")
    void c4_paymentGatewayUrl_isBoundFromPlaceholder() {
        assertThat(environment.getProperty("payment.gateway.url")).isEqualTo(PG.baseUrl());
        long orderId = orderIdOk(uid("u"), orderBody(null, item(product(1_000, 5), 1)));

        payOk(orderId);

        assertThat(PG.paymentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("C4 ORDER_PAYMENT_TTL 을 지정하지 않으면 기본값 PT15M 이다")
    void c4_orderPaymentTtl_defaultsToPT15M() {
        assertThat(Duration.parse(environment.getProperty("order.payment-ttl"))).isEqualTo(Duration.ofMinutes(15));
    }
}
