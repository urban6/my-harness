package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R2 쿠폰")
class R02CouponApiTest extends AbstractIntegrationTest {

    private static final OffsetDateTime FROM = OffsetDateTime.now().minusDays(1);
    private static final OffsetDateTime UNTIL = OffsetDateTime.now().plusDays(30);

    private ApiResponse create(String code, String type, long value, Long min, Long max, long total) {
        return createCoupon(couponBody(code, type, value, min, max, total, FROM, UNTIL));
    }

    // ------------------------------------------------------------- R2.1 / R2.3

    @Test
    @DisplayName("R2.1 쿠폰 등록 -> 201, Location, usedCount=0, 입력값 반영")
    void create_returns201WithLocation() {
        String code = uniqueCode();

        ApiResponse r = create(code, "RATE", 10, 5_000L, 3_000L, 7);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/coupons/" + code);
        assertThat(r.json("code").asText()).isEqualTo(code);
        assertThat(r.json("type").asText()).isEqualTo("RATE");
        assertThat(r.json("value").asLong()).isEqualTo(10);
        assertThat(r.json("minOrderAmount").asLong()).isEqualTo(5_000);
        assertThat(r.json("maxDiscountAmount").asLong()).isEqualTo(3_000);
        assertThat(r.json("totalQuantity").asLong()).isEqualTo(7);
        assertThat(r.json("usedCount").asLong()).isZero();
        assertThat(instant(r.json("validFrom"))).isEqualTo(FROM.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(instant(r.json("validUntil"))).isEqualTo(UNTIL.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 생략 -> 0, maxDiscountAmount 생략 -> null")
    void create_defaultsWhenOmitted() {
        ApiResponse r = create(uniqueCode(), "FIXED", 1_000, null, null, 1);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("minOrderAmount").asLong()).isZero();
        assertThat(r.json("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.3 쿠폰 조회 -> 200, 등록 응답과 같은 형태")
    void get_returnsSameShapeAsCreate() {
        ApiResponse created = create(uniqueCode(), "FIXED", 500, 1_000L, null, 3);

        ApiResponse fetched = getCoupon(created.json("code").asText());

        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body().fieldNames()).toIterable().containsExactlyInAnyOrder("code", "type", "value",
                "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount", "validFrom", "validUntil");
        assertThat(fetched.body()).isEqualTo(created.body());
    }

    @Test
    @DisplayName("R2.3 오프셋이 다른 입력 시각도 같은 순간으로 저장/응답")
    void create_nonUtcOffsetInput_sameInstant() {
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1);
        OffsetDateTime until = from.plusDays(5);
        String code = uniqueCode();

        ApiResponse r = createCoupon(couponBody(code, "FIXED", 10, 0L, null, 1, from, until));

        assertThat(r.status()).isEqualTo(201);
        assertThat(instant(getCoupon(code).json("validFrom")))
                .isEqualTo(from.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰 -> 404 COUPON_NOT_FOUND")
    void get_unknown_404() {
        ApiResponse r = getCoupon("NOSUCH" + System.nanoTime());

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("COUPON_NOT_FOUND");
    }

    // ------------------------------------------------------------- R2.2 validation

    @Test
    @DisplayName("R2.2 이미 있는 code -> 409 DUPLICATE_COUPON_CODE")
    void create_duplicateCode_409() {
        String code = uniqueCode();
        assertThat(create(code, "FIXED", 100, null, null, 1).status()).isEqualTo(201);

        ApiResponse r = create(code, "RATE", 5, null, null, 9);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("DUPLICATE_COUPON_CODE");
        assertThat(getCoupon(code).json("type").asText()).isEqualTo("FIXED");
    }

    @Test
    @DisplayName("R2.2 동시에 같은 code 등록 -> 정확히 1건 201, 나머지 409 DUPLICATE_COUPON_CODE")
    void create_concurrentDuplicate_oneWins() {
        String code = uniqueCode();
        java.util.List<ApiResponse> rs = runConcurrently(java.util.stream.IntStream.range(0, 6)
                .<java.util.concurrent.Callable<ApiResponse>>mapToObj(i -> () -> create(code, "FIXED", 100, null, null, 1))
                .toList());

        assertThat(countStatus(rs, 201)).isEqualTo(1);
        assertThat(countCode(rs, 409, "DUPLICATE_COUPON_CODE")).isEqualTo(5);
    }

    @ParameterizedTest(name = "R2.2 code [{0}] -> 400")
    @ValueSource(strings = {"abc1", "ABC", "AB-CD", "ABCD_1", "한글한글한글", "ABC D"})
    void create_invalidCode_400(String code) {
        ApiResponse r = create(code, "FIXED", 100, null, null, 1);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R2.2 code 길이 {0} 허용")
    @ValueSource(ints = {4, 20})
    void create_codeLengthBoundaries_ok(int len) {
        ApiResponse r = create(randomCode(len), "FIXED", 100, null, null, 1);

        assertThat(r.status()).isEqualTo(201);
    }

    @ParameterizedTest(name = "R2.2 code 길이 {0} -> 400")
    @ValueSource(ints = {3, 21})
    void create_codeLengthViolations_400(int len) {
        ApiResponse r = create(randomCode(len), "FIXED", 100, null, null, 1);

        assertThat(r.status()).isEqualTo(400);
    }

    private static String randomCode(int len) {
        String s = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).replace("-", "")
                .toUpperCase();
        return s.substring(0, len);
    }

    @ParameterizedTest(name = "R2.2 type [{0}] -> 400")
    @ValueSource(strings = {"fixed", "PERCENT", "rate", ""})
    void create_invalidType_400(String type) {
        ApiResponse r = create(uniqueCode(), type, 10, null, null, 1);

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R2.2 FIXED value 0 -> 400, 1 -> 201")
    void create_fixedValueBoundary() {
        assertThat(create(uniqueCode(), "FIXED", 0, null, null, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "FIXED", -5, null, null, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "FIXED", 1, null, null, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 FIXED value 는 100 초과도 허용 (int 범위 초과 포함)")
    void create_fixedValueAbove100_ok() {
        assertThat(create(uniqueCode(), "FIXED", 5_000_000_000L, null, null, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 RATE value 0 / 101 -> 400, 1 / 100 -> 201")
    void create_rateValueBoundary() {
        assertThat(create(uniqueCode(), "RATE", 0, null, null, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "RATE", 101, null, null, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "RATE", 1, null, null, 1).status()).isEqualTo(201);
        assertThat(create(uniqueCode(), "RATE", 100, null, null, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 minOrderAmount -1 -> 400, 0 -> 201")
    void create_minOrderAmountBoundary() {
        assertThat(create(uniqueCode(), "FIXED", 10, -1L, null, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "FIXED", 10, 0L, null, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 maxDiscountAmount 0 -> 400, 1 -> 201")
    void create_maxDiscountBoundary() {
        assertThat(create(uniqueCode(), "FIXED", 10, null, 0L, 1).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "FIXED", 10, null, 1L, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 totalQuantity 0 -> 400, 1 -> 201")
    void create_totalQuantityBoundary() {
        assertThat(create(uniqueCode(), "FIXED", 10, null, null, 0).status()).isEqualTo(400);
        assertThat(create(uniqueCode(), "FIXED", 10, null, null, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 validFrom >= validUntil -> 400")
    void create_invalidRange_400() {
        OffsetDateTime t = OffsetDateTime.now();

        ApiResponse same = createCoupon(couponBody(uniqueCode(), "FIXED", 10, null, null, 1, t, t));
        ApiResponse reversed = createCoupon(couponBody(uniqueCode(), "FIXED", 10, null, null, 1, t, t.minusSeconds(1)));
        ApiResponse ok = createCoupon(couponBody(uniqueCode(), "FIXED", 10, null, null, 1, t, t.plusSeconds(1)));

        assertThat(same.status()).isEqualTo(400);
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 같은 순간을 다른 오프셋으로 표현한 from/until -> 400")
    void create_sameInstantDifferentOffsets_400() {
        OffsetDateTime a = OffsetDateTime.parse("2030-01-01T10:00:00+09:00");
        OffsetDateTime b = OffsetDateTime.parse("2030-01-01T01:00:00Z");

        ApiResponse r = createCoupon(couponBody(uniqueCode(), "FIXED", 10, null, null, 1, a, b));

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R2.2 필수 필드 누락 -> 400")
    void create_missingRequiredFields_400() {
        for (String missing : new String[] {"code", "type", "value", "totalQuantity", "validFrom", "validUntil"}) {
            Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 10, null, null, 1, FROM, UNTIL);
            body.remove(missing);

            ApiResponse r = createCoupon(body);

            assertThat(r.status()).as("missing %s", missing).isEqualTo(400);
        }
    }

    // ------------------------------------------------------------- R2.4 discount

    private ApiResponse orderWithCoupon(String code, long unitPrice, int qty) {
        long p = newProduct(unitPrice, qty + 5);
        return createOrder(uniqueUser(), uniqueKey(), code, p, qty);
    }

    @Test
    @DisplayName("R2.4 FIXED: discount=value, totalPrice=subtotal-discount")
    void discount_fixed() {
        String code = newCoupon("FIXED", 3_000, null, null, 5);

        ApiResponse r = orderWithCoupon(code, 5_000, 2);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("subtotal").asLong()).isEqualTo(10_000);
        assertThat(r.json("discount").asLong()).isEqualTo(3_000);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(7_000);
        assertThat(r.json("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("R2.4 RATE: floor(subtotal*value/100)")
    void discount_rateFloors() {
        String code = newCoupon("RATE", 15, null, null, 5);

        ApiResponse r = orderWithCoupon(code, 333, 3); // subtotal 999 -> 149.85

        assertThat(r.json("subtotal").asLong()).isEqualTo(999);
        assertThat(r.json("discount").asLong()).isEqualTo(149);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(850);
    }

    @Test
    @DisplayName("R2.4 RATE 1% of 99 -> 0 (floor), 할인 없이 주문 성공")
    void discount_rateFloorsToZero() {
        String code = newCoupon("RATE", 1, null, null, 5);

        ApiResponse r = orderWithCoupon(code, 99, 1);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("discount").asLong()).isZero();
        assertThat(r.json("totalPrice").asLong()).isEqualTo(99);
    }

    @Test
    @DisplayName("R2.4 RATE + maxDiscountAmount 상한")
    void discount_rateCappedByMax() {
        String code = newCoupon("RATE", 50, null, 1_000L, 5);

        ApiResponse r = orderWithCoupon(code, 10_000, 1); // 5000 -> cap 1000

        assertThat(r.json("discount").asLong()).isEqualTo(1_000);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(9_000);
    }

    @Test
    @DisplayName("R2.4 FIXED + maxDiscountAmount 상한")
    void discount_fixedCappedByMax() {
        String code = newCoupon("FIXED", 5_000, null, 2_000L, 5);

        ApiResponse r = orderWithCoupon(code, 10_000, 1);

        assertThat(r.json("discount").asLong()).isEqualTo(2_000);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("R2.4 max 가 더 크면 원래 할인액 유지")
    void discount_maxAboveDiscountNotApplied() {
        String code = newCoupon("FIXED", 500, null, 100_000L, 5);

        ApiResponse r = orderWithCoupon(code, 10_000, 1);

        assertThat(r.json("discount").asLong()).isEqualTo(500);
    }

    @Test
    @DisplayName("R2.4 FIXED 가 subtotal 보다 크면 subtotal 로 상한 -> totalPrice 0")
    void discount_cappedBySubtotal() {
        String code = newCoupon("FIXED", 50_000, null, null, 5);

        ApiResponse r = orderWithCoupon(code, 1_000, 2);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("discount").asLong()).isEqualTo(2_000);
        assertThat(r.json("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.4 maxDiscountAmount 가 subtotal 보다 커도 subtotal 로 상한")
    void discount_maxAboveSubtotal_cappedBySubtotal() {
        String code = newCoupon("FIXED", 90_000, null, 80_000L, 5);

        ApiResponse r = orderWithCoupon(code, 1_000, 2);

        assertThat(r.json("discount").asLong()).isEqualTo(2_000);
        assertThat(r.json("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.4 RATE 100% -> discount=subtotal")
    void discount_rate100() {
        String code = newCoupon("RATE", 100, null, null, 5);

        ApiResponse r = orderWithCoupon(code, 700, 3);

        assertThat(r.json("discount").asLong()).isEqualTo(2_100);
        assertThat(r.json("totalPrice").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.4 / C1 int 범위를 넘는 subtotal 에서도 RATE 계산이 정확")
    void discount_bigSubtotalBeyondInt() {
        String code = newCoupon("RATE", 33, null, null, 5);
        long p = newProduct(10_000_000, 1_000);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1_000);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("subtotal").asLong()).isEqualTo(10_000_000_000L);
        assertThat(r.json("discount").asLong()).isEqualTo(3_300_000_000L);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(6_700_000_000L);
    }

    @Test
    @DisplayName("R2.4 여러 품목의 subtotal 은 Σ(unitPrice*quantity)")
    void discount_multiItemSubtotal() {
        String code = newCoupon("FIXED", 100, null, null, 5);
        long a = newProduct(1_500, 10);
        long b = newProduct(250, 10);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, a, 2, b, 4);

        assertThat(r.json("subtotal").asLong()).isEqualTo(4_000);
        assertThat(r.json("discount").asLong()).isEqualTo(100);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(3_900);
    }

    // ------------------------------------------------------------- R2.5 conditions

    @Test
    @DisplayName("R2.5 validFrom 이전(미래 시작) 쿠폰 -> 409 COUPON_NOT_APPLICABLE, 예약/사용 불변")
    void apply_beforeValidFrom_notApplicable() {
        String code = uniqueCode();
        createCoupon(couponBody(code, "FIXED", 100, null, null, 5, OffsetDateTime.now().plusDays(1),
                OffsetDateTime.now().plusDays(2)));
        long p = newProduct(1_000, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(reservedOf(p)).isZero();
        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R2.5 validUntil 이후(만료) 쿠폰 -> 409 COUPON_NOT_APPLICABLE")
    void apply_afterValidUntil_notApplicable() {
        String code = uniqueCode();
        createCoupon(couponBody(code, "FIXED", 100, null, null, 5, OffsetDateTime.now().minusDays(2),
                OffsetDateTime.now().minusSeconds(1)));
        long p = newProduct(1_000, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal < minOrderAmount -> 409 COUPON_NOT_APPLICABLE")
    void apply_belowMinOrder_notApplicable() {
        String code = newCoupon("FIXED", 100, 10_001L, null, 5);
        long p = newProduct(10_000, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(usedCountOf(code)).isZero();
    }

    @Test
    @DisplayName("R2.5 subtotal == minOrderAmount 는 사용 가능")
    void apply_equalToMinOrder_ok() {
        String code = newCoupon("FIXED", 100, 10_000L, null, 5);
        long p = newProduct(10_000, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 minOrderAmount 비교는 할인 전 subtotal 기준")
    void apply_minOrderComparedWithSubtotalNotTotal() {
        String code = newCoupon("FIXED", 9_000, 10_000L, null, 5);
        long p = newProduct(10_000, 5);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("totalPrice").asLong()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이미 사용 중이면 409 COUPON_NOT_APPLICABLE, 다른 사용자는 가능")
    void apply_sameUserTwice_notApplicable_otherUserOk() {
        String code = newCoupon("FIXED", 100, null, null, 5);
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        newOrder(user, code, p, 1);

        ApiResponse again = createOrder(user, uniqueKey(), code, p, 1);
        ApiResponse other = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(other.status()).isEqualTo(201);
        assertThat(usedCountOf(code)).isEqualTo(2);
        assertThat(reservedOf(p)).isEqualTo(2);
    }

    @Test
    @DisplayName("R2.5 결제 완료(PAID) 주문도 사용 중이므로 같은 사용자는 재사용 불가")
    void apply_sameUserWhilePaid_notApplicable() {
        String code = newCoupon("FIXED", 100, null, null, 5);
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        newPaidOrder(user, code, p, 1);

        ApiResponse again = createOrder(user, uniqueKey(), code, p, 1);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity -> 409 COUPON_EXHAUSTED")
    void apply_exhausted() {
        String code = newCoupon("FIXED", 100, null, null, 2);
        long p = newProduct(1_000, 10);
        newOrder(uniqueUser(), code, p, 1);
        newOrder(uniqueUser(), code, p, 1);

        ApiResponse r = createOrder(uniqueUser(), uniqueKey(), code, p, 1);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("COUPON_EXHAUSTED");
        assertThat(usedCountOf(code)).isEqualTo(2);
        assertThat(reservedOf(p)).isEqualTo(2);
    }

    // ------------------------------------------------------------- R2.6 restore

    @Test
    @DisplayName("R2.6 PENDING 주문 취소(CANCELLED) -> usedCount 복원, 같은 사용자 재사용 가능")
    void restore_afterCancel_sameUserCanReuse() {
        String code = newCoupon("FIXED", 100, null, null, 1);
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse first = newOrder(user, code, p, 1);
        assertThat(usedCountOf(code)).isEqualTo(1);

        assertThat(cancel(first.id()).status()).isEqualTo(200);

        assertThat(usedCountOf(code)).isZero();
        assertThat(createOrder(user, uniqueKey(), code, p, 1).status()).isEqualTo(201);
        assertThat(usedCountOf(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("R2.6 결제 거절(PAYMENT_FAILED) -> usedCount 복원, 같은 사용자 재사용 가능")
    void restore_afterDecline_sameUserCanReuse() {
        String code = newCoupon("FIXED", 100, null, null, 1);
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse first = newOrder(user, code, p, 1);
        stubPgPayment("DECLINED", "pay-declined");

        assertThat(pay(first.id(), uniqueKey(), "tok").status()).isEqualTo(402);

        assertThat(usedCountOf(code)).isZero();
        assertThat(createOrder(user, uniqueKey(), code, p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 환불(REFUNDED) -> usedCount 복원, 같은 사용자 재사용 가능")
    void restore_afterRefund_sameUserCanReuse() {
        String code = newCoupon("FIXED", 100, null, null, 1);
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse paid = newPaidOrder(user, code, p, 1);
        assertThat(usedCountOf(code)).isEqualTo(1);
        WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/v1/payments/.+/refund"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                        .withBody("{\"paymentId\":\"x\",\"status\":\"REFUNDED\"}")
                        .withHeader("Content-Type", "application/json")));

        assertThat(cancel(paid.id()).status()).isEqualTo(200);

        assertThat(usedCountOf(code)).isZero();
        assertThat(createOrder(user, uniqueKey(), code, p, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 PAID/SHIPPED/DELIVERED 주문은 사용을 유지한다")
    void restore_notRestoredWhilePaidShippedDelivered() {
        String code = newCoupon("FIXED", 100, null, null, 3);
        long p = newProduct(1_000, 10);
        ApiResponse paid = newPaidOrder(uniqueUser(), code, p, 1);
        assertThat(usedCountOf(code)).isEqualTo(1);

        ship(paid.id());
        assertThat(usedCountOf(code)).isEqualTo(1);
        deliver(paid.id());
        assertThat(usedCountOf(code)).isEqualTo(1);
    }
}
