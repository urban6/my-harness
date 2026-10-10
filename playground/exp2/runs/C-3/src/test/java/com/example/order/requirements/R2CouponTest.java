package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R2. 쿠폰")
class R2CouponTest extends IntegrationTestBase {

    /** 모든 필드가 유효한 기본 쿠폰 본문. 테스트가 필요한 필드만 덮어쓴다. */
    private Map<String, Object> validBody(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("type", "FIXED");
        m.put("value", 1000);
        m.put("minOrderAmount", 0);
        m.put("maxDiscountAmount", 5000);
        m.put("totalQuantity", 10);
        m.put("validFrom", Instant.now().minusSeconds(3600).toString());
        m.put("validUntil", Instant.now().plusSeconds(86_400).toString());
        return m;
    }

    private ResponseEntity<String> postWith(String key, Object value) {
        Map<String, Object> m = validBody(uniqueCode("V"));
        if (value == null) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
        return post("/api/coupons", m);
    }

    // ---------------------------------------------------------------- R2.1

    @Test
    @DisplayName("R2.1 쿠폰을 등록하면 201 과 Location(/api/coupons/{code}) 을 돌려준다")
    void r2_1_create_returns201WithLocation() {
        String code = uniqueCode("LOC");

        ResponseEntity<String> r = post("/api/coupons", validBody(code));

        assertThat(statusOf(r)).isEqualTo(201);
        assertThat(r.getHeaders().getLocation()).hasToString("/api/coupons/" + code);
    }

    @Test
    @DisplayName("R2.1 등록 응답은 조회와 같은 형태이고 usedCount 는 0 이다")
    void r2_1_create_bodyEqualsGet_withUsedCountZero() {
        String code = uniqueCode("SHAPE");

        JsonNode created = json(post("/api/coupons", validBody(code)));

        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "type", "value",
                "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount", "validFrom", "validUntil");
        assertThat(created.get("usedCount").asLong()).isZero();
        assertThat(json(getCoupon(code))).isEqualTo(created);
    }

    @Test
    @DisplayName("R2.1 minOrderAmount 를 생략하면 0, maxDiscountAmount 를 생략하면 null(제한 없음)")
    void r2_1_create_defaultsForOptionalFields() {
        Map<String, Object> m = validBody(uniqueCode("DEF"));
        m.remove("minOrderAmount");
        m.remove("maxDiscountAmount");

        JsonNode created = json(post("/api/coupons", m));

        assertThat(created.get("minOrderAmount").asLong()).isZero();
        assertThat(created.get("maxDiscountAmount").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- R2.2

    @ParameterizedTest(name = "[{index}] code 길이 {0} -> 201")
    @CsvSource({"4", "20"})
    @DisplayName("R2.2 code 길이 경계값(4자, 20자)은 허용된다")
    void r2_2_code_lengthBoundaries_returns201(int length) {
        String code = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, length);
        if (length == 20) {
            code = (code + "ABCDEFGHIJKLMNOPQRST").substring(0, 20);
        }

        assertThat(statusOf(post("/api/coupons", validBody(code)))).isEqualTo(201);
    }

    @ParameterizedTest(name = "[{index}] code={0} -> 400")
    @CsvSource({"ABC", "A1B2C3D4E5F6G7H8I9J0K", "abcd", "AbCd1", "AB-CD", "AB_CD", "AB CD", "한글코드쿠폰"})
    @DisplayName("R2.2 code 가 3자 이하, 21자 이상이거나 대문자·숫자 외 문자를 포함하면 400")
    void r2_2_code_invalidFormat_returns400(String code) {
        assertProblem(post("/api/coupons", validBody(code)), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 code 가 없으면 400")
    void r2_2_code_missing_returns400() {
        assertProblem(postWith("code", null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 type 이 FIXED/RATE 가 아니면 400")
    void r2_2_type_unknown_returns400() {
        assertProblem(postWith("type", "PERCENT"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 type 이 소문자(rate)이면 400")
    void r2_2_type_lowercase_returns400() {
        assertProblem(postWith("type", "rate"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 type 이 없으면 400")
    void r2_2_type_missing_returns400() {
        assertProblem(postWith("type", null), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] FIXED value={0} -> {1}")
    @CsvSource({"1,201", "100000,201", "5000000000,201", "0,400", "-5,400"})
    @DisplayName("R2.2 FIXED 쿠폰의 value 는 1 이상 (상한 없음)")
    void r2_2_fixedValue(long value, int expectedStatus) {
        assertThat(statusOf(postWith("value", value))).isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "[{index}] RATE value={0} -> {1}")
    @CsvSource({"1,201", "100,201", "0,400", "101,400", "1000,400", "-1,400"})
    @DisplayName("R2.2 RATE 쿠폰의 value 는 1 ~ 100")
    void r2_2_rateValue(long value, int expectedStatus) {
        Map<String, Object> m = validBody(uniqueCode("RV"));
        m.put("type", "RATE");
        m.put("value", value);

        assertThat(statusOf(post("/api/coupons", m))).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("R2.2 value 가 없으면 400")
    void r2_2_value_missing_returns400() {
        assertProblem(postWith("value", null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 가 음수이면 400")
    void r2_2_minOrderAmount_negative_returns400() {
        assertProblem(postWith("minOrderAmount", -1), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 가 0 이면 허용")
    void r2_2_minOrderAmount_zero_returns201() {
        assertThat(statusOf(postWith("minOrderAmount", 0))).isEqualTo(201);
    }

    @ParameterizedTest(name = "[{index}] maxDiscountAmount={0}")
    @CsvSource({"0", "-1"})
    @DisplayName("R2.2 maxDiscountAmount 가 1 미만이면 400")
    void r2_2_maxDiscountAmount_belowOne_returns400(long value) {
        assertProblem(postWith("maxDiscountAmount", value), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 maxDiscountAmount 가 1 이면 허용")
    void r2_2_maxDiscountAmount_one_returns201() {
        assertThat(statusOf(postWith("maxDiscountAmount", 1))).isEqualTo(201);
    }

    @ParameterizedTest(name = "[{index}] totalQuantity={0} -> {1}")
    @CsvSource({"1,201", "0,400", "-3,400"})
    @DisplayName("R2.2 totalQuantity 는 1 이상")
    void r2_2_totalQuantity(long value, int expectedStatus) {
        assertThat(statusOf(postWith("totalQuantity", value))).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("R2.2 validFrom 과 validUntil 이 같으면 400")
    void r2_2_period_equal_returns400() {
        Instant t = Instant.now();
        Map<String, Object> m = validBody(uniqueCode("EQ"));
        m.put("validFrom", t.toString());
        m.put("validUntil", t.toString());

        assertProblem(post("/api/coupons", m), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 validFrom 이 validUntil 보다 늦으면 400")
    void r2_2_period_reversed_returns400() {
        Instant t = Instant.now();
        Map<String, Object> m = validBody(uniqueCode("REV"));
        m.put("validFrom", t.plusSeconds(100).toString());
        m.put("validUntil", t.toString());

        assertProblem(post("/api/coupons", m), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 validFrom 이 validUntil 보다 1초만 빨라도 허용")
    void r2_2_period_oneSecond_returns201() {
        Instant t = Instant.now();
        Map<String, Object> m = validBody(uniqueCode("ONE"));
        m.put("validFrom", t.toString());
        m.put("validUntil", t.plusSeconds(1).toString());

        assertThat(statusOf(post("/api/coupons", m))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 validFrom/validUntil 이 없으면 400")
    void r2_2_period_missing_returns400() {
        assertProblem(postWith("validFrom", null), 400, "VALIDATION_ERROR");
        assertProblem(postWith("validUntil", null), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.2 이미 있는 code 로 등록하면 409 DUPLICATE_COUPON_CODE")
    void r2_2_duplicateCode_returns409() {
        String code = uniqueCode("DUP");
        post("/api/coupons", validBody(code));

        assertProblem(post("/api/coupons", validBody(code)), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.2 중복 등록이 실패해도 기존 쿠폰은 변하지 않는다")
    void r2_2_duplicateCode_keepsOriginal() {
        String code = uniqueCode("KEEP");
        post("/api/coupons", validBody(code));
        Map<String, Object> other = validBody(code);
        other.put("value", 777);

        post("/api/coupons", other);

        assertThat(json(getCoupon(code)).get("value").asLong()).isEqualTo(1000L);
    }

    // ---------------------------------------------------------------- R2.3

    @Test
    @DisplayName("R2.3 조회하면 등록한 값이 200 으로 돌려진다")
    void r2_3_get_returnsStoredFields() {
        String code = uniqueCode("GET");
        createCoupon(code, "RATE", 15, 3000, 2000L, 8);

        ResponseEntity<String> r = getCoupon(code);

        assertThat(statusOf(r)).isEqualTo(200);
        JsonNode c = json(r);
        assertThat(c.get("code").asText()).isEqualTo(code);
        assertThat(c.get("type").asText()).isEqualTo("RATE");
        assertThat(c.get("value").asLong()).isEqualTo(15L);
        assertThat(c.get("minOrderAmount").asLong()).isEqualTo(3000L);
        assertThat(c.get("maxDiscountAmount").asLong()).isEqualTo(2000L);
        assertThat(c.get("totalQuantity").asLong()).isEqualTo(8L);
        assertThat(c.get("usedCount").asLong()).isZero();
    }

    @Test
    @DisplayName("R2.3 없는 code 는 404 COUPON_NOT_FOUND")
    void r2_3_get_unknownCode_returns404() {
        assertProblem(getCoupon("NOSUCH" + uniqueCode("").substring(0, 8)), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.3 code 는 대소문자를 구분한다 (소문자로 조회하면 404)")
    void r2_3_get_isCaseSensitive() {
        String code = uniqueCode("CASE");
        createCoupon(code, "FIXED", 100, 0, null, 1);

        assertProblem(getCoupon(code.toLowerCase()), 404, "COUPON_NOT_FOUND");
    }

    // ---------------------------------------------------------------- R2.4 (할인 계산)

    /** (type, value, maxDiscount or -1, unitPrice, quantity, expectedDiscount) */
    @ParameterizedTest(name = "[{index}] {0} value={1} max={2} subtotal={3}x{4} -> discount={5}")
    @CsvSource({
            // FIXED: value 그대로
            "FIXED,1000,-1,10000,1,1000",
            // RATE: floor(subtotal*value/100)  9999*15/100 = 1499.85 -> 1499
            "RATE,15,-1,9999,1,1499",
            // RATE: 정수로 딱 떨어지는 경우
            "RATE,10,-1,5000,2,1000",
            // RATE 1%: floor(99*1/100)=0 -> 할인 0
            "RATE,1,-1,99,1,0",
            // max 상한 (RATE)
            "RATE,50,2000,10000,1,2000",
            // max 상한 (FIXED)
            "FIXED,5000,3000,10000,1,3000",
            // max 가 더 커서 상한이 적용되지 않음
            "RATE,10,9999,10000,1,1000",
            // subtotal 상한: FIXED value > subtotal
            "FIXED,50000,-1,10000,1,10000",
            // subtotal 상한: max 도 subtotal 보다 큼
            "FIXED,20000,30000,10000,1,10000",
            // RATE 100% = subtotal 전액
            "RATE,100,-1,12345,1,12345",
            // RATE 100% 인데 max 로 상한
            "RATE,100,500,12345,1,500",
            // 수량이 곱해진 subtotal 기준
            "RATE,20,-1,3333,3,1999"
    })
    @DisplayName("R2.4 할인액 = (FIXED: value | RATE: floor(subtotal*value/100)) -> max 상한 -> subtotal 상한")
    void r2_4_discountCalculation(String type, long value, long max, long unitPrice, int quantity, long expectedDiscount) {
        long productId = createProduct(uid("p"), unitPrice, 100);
        String code = uniqueCode("CALC");
        createCoupon(code, type, value, 0, max < 0 ? null : max, 5);

        JsonNode order = orderOk(uid("u"), orderBody(code, item(productId, quantity)));

        long subtotal = unitPrice * quantity;
        assertThat(order.get("subtotal").asLong()).isEqualTo(subtotal);
        assertThat(order.get("discount").asLong()).isEqualTo(expectedDiscount);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(subtotal - expectedDiscount);
    }

    @Test
    @DisplayName("R2.4 subtotal 은 여러 항목의 Σ(unitPrice × quantity) 이다")
    void r2_4_subtotal_sumsAllItems() {
        long p1 = createProduct(uid("p"), 1_500, 10);
        long p2 = createProduct(uid("p"), 700, 10);
        String code = uniqueCode("SUM");
        createCoupon(code, "RATE", 10, 0, null, 5);

        JsonNode order = orderOk(uid("u"), orderBody(code, item(p1, 2), item(p2, 3)));

        assertThat(order.get("subtotal").asLong()).isEqualTo(5_100L);
        assertThat(order.get("discount").asLong()).isEqualTo(510L);
        assertThat(order.get("totalPrice").asLong()).isEqualTo(4_590L);
    }

    @Test
    @DisplayName("R2.4 쿠폰이 없으면 discount 0, totalPrice = subtotal")
    void r2_4_noCoupon_discountIsZero() {
        long productId = createProduct(uid("p"), 4_000, 10);

        JsonNode order = orderOk(uid("u"), orderBody(null, item(productId, 2)));

        assertThat(order.get("discount").asLong()).isZero();
        assertThat(order.get("totalPrice").asLong()).isEqualTo(8_000L);
    }

    // ---------------------------------------------------------------- R2.5 (적용 불가)

    @Test
    @DisplayName("R2.5 validFrom 이전이면 409 COUPON_NOT_APPLICABLE")
    void r2_5_beforeValidFrom_returns409() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("FUT");
        createCouponWithPeriod(code, "FIXED", 1000, 0, null, 5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 validUntil 이후이면 409 COUPON_NOT_APPLICABLE")
    void r2_5_afterValidUntil_returns409() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("PAST");
        createCouponWithPeriod(code, "FIXED", 1000, 0, null, 5, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 validUntil 은 배타 경계다 (만료 직전까지 201, validUntil 이후 409)")
    void r2_5_validUntilBoundary_turnsNotApplicable() {
        long productId = product(10_000, 10);
        String code = uniqueCode("UNTIL");
        Instant until = Instant.now().plusMillis(2_500);
        createCouponWithPeriod(code, "FIXED", 1000, 0, null, 5, Instant.now().minusSeconds(60), until);
        assertThat(statusOf(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);

        sleepMillis(Math.max(0, until.toEpochMilli() - System.currentTimeMillis()) + 100);

        assertProblem(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 validFrom 은 포함 경계다 (시작 전 409, validFrom 이후 201)")
    void r2_5_validFromBoundary_turnsApplicable() {
        long productId = product(10_000, 10);
        String code = uniqueCode("FROM");
        Instant from = Instant.now().plusMillis(2_500);
        createCouponWithPeriod(code, "FIXED", 1000, 0, null, 5, from, from.plusSeconds(3600));
        assertProblem(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))), 409, "COUPON_NOT_APPLICABLE");

        sleepMillis(Math.max(0, from.toEpochMilli() - System.currentTimeMillis()) + 100);

        assertThat(statusOf(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 subtotal 이 minOrderAmount 미달이면 409 COUPON_NOT_APPLICABLE")
    void r2_5_belowMinOrderAmount_returns409() {
        long productId = createProduct(uid("p"), 4_999, 10);
        String code = uniqueCode("MIN");
        createCoupon(code, "FIXED", 1000, 5_000, null, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(r, 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 subtotal 이 minOrderAmount 와 정확히 같으면 적용된다 (경계값)")
    void r2_5_equalToMinOrderAmount_isApplicable() {
        long productId = createProduct(uid("p"), 5_000, 10);
        String code = uniqueCode("MINEQ");
        createCoupon(code, "FIXED", 1000, 5_000, null, 5);

        ResponseEntity<String> r = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertThat(statusOf(r)).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 같은 사용자가 이 쿠폰을 사용 중인 주문이 있으면 409 COUPON_NOT_APPLICABLE")
    void r2_5_sameUserAlreadyUsing_returns409() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("SAMEU");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        String user = uid("u");
        orderOk(user, orderBody(code, item(productId, 1)));

        ResponseEntity<String> second = createOrder(user, uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(second, 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 결제 완료(PAID) 주문도 사용 중으로 보아 같은 사용자는 재사용할 수 없다")
    void r2_5_sameUserWithPaidOrder_returns409() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("SAMEP");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        String user = uid("u");
        payOk(orderIdOk(user, orderBody(code, item(productId, 1))));

        ResponseEntity<String> second = createOrder(user, uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(second, 409, "COUPON_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("R2.5 다른 사용자는 같은 쿠폰을 사용할 수 있다")
    void r2_5_differentUser_canUseSameCoupon() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("DIFFU");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        orderOk(uid("u"), orderBody(code, item(productId, 1)));

        ResponseEntity<String> other = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertThat(statusOf(other)).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.5 usedCount == totalQuantity 이면 409 COUPON_EXHAUSTED")
    void r2_5_exhausted_returns409() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("EXH");
        createCoupon(code, "FIXED", 1000, 0, null, 2);
        orderOk(uid("u"), orderBody(code, item(productId, 1)));
        orderOk(uid("u"), orderBody(code, item(productId, 1)));

        ResponseEntity<String> third = createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertProblem(third, 409, "COUPON_EXHAUSTED");
        assertThat(usedCountOf(code)).isEqualTo(2L);
    }

    @Test
    @DisplayName("R2.5 쿠폰 적용에 실패하면 usedCount 와 reserved 는 변하지 않는다")
    void r2_5_notApplicable_leavesCountersUntouched() {
        long productId = createProduct(uid("p"), 1_000, 10);
        String code = uniqueCode("NOCH");
        createCoupon(code, "FIXED", 100, 50_000, null, 5);

        createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1)));

        assertThat(usedCountOf(code)).isZero();
        assertThat(reservedOf(productId)).isZero();
    }

    // ---------------------------------------------------------------- R2.6 (복원)

    @Test
    @DisplayName("R2.6 주문을 만들면 usedCount 가 1 늘고, 결제 완료 후에도 유지된다")
    void r2_6_usedCount_incrementsOnOrder_andStaysAfterPayment() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("INC");
        createCoupon(code, "FIXED", 1000, 0, null, 5);

        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        assertThat(usedCountOf(code)).isEqualTo(1L);
        payOk(orderId);

        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R2.6 취소(CANCELLED)하면 usedCount 가 복원되고 같은 사용자가 다시 쓸 수 있다")
    void r2_6_cancelled_restoresUsage() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("CNL");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        String user = uid("u");
        long orderId = orderIdOk(user, orderBody(code, item(productId, 1)));

        assertThat(statusOf(cancel(orderId))).isEqualTo(200);

        assertThat(usedCountOf(code)).isZero();
        assertThat(statusOf(createOrder(user, uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);
        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R2.6 결제 거절(PAYMENT_FAILED)이면 usedCount 가 복원되고 같은 사용자가 다시 쓸 수 있다")
    void r2_6_paymentFailed_restoresUsage() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("DEC");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        String user = uid("u");
        long orderId = orderIdOk(user, orderBody(code, item(productId, 1)));
        PG.declinePayments();

        assertThat(statusOf(pay(orderId, uid("pk"), "tok_declined"))).isEqualTo(402);

        assertThat(usedCountOf(code)).isZero();
        assertThat(statusOf(createOrder(user, uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 환불(REFUNDED)되면 usedCount 가 복원되고 같은 사용자가 다시 쓸 수 있다")
    void r2_6_refunded_restoresUsage() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("RFD");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        String user = uid("u");
        long orderId = orderIdOk(user, orderBody(code, item(productId, 1)));
        payOk(orderId);

        assertThat(statusOf(cancel(orderId))).isEqualTo(200);

        assertThat(usedCountOf(code)).isZero();
        assertThat(statusOf(createOrder(user, uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.6 PG 장애(503)로 결제가 실패해도 usedCount 는 그대로다")
    void r2_6_gatewayFailure_keepsUsage() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("GWF");
        createCoupon(code, "FIXED", 1000, 0, null, 5);
        long orderId = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        PG.failPaymentsWith5xx();

        assertThat(statusOf(pay(orderId, uid("pk"), "tok"))).isEqualTo(503);

        assertThat(usedCountOf(code)).isEqualTo(1L);
    }

    @Test
    @DisplayName("R2.6 소진된 쿠폰도 주문이 취소되면 다시 사용할 수 있다")
    void r2_6_exhaustedCoupon_becomesUsableAfterCancel() {
        long productId = createProduct(uid("p"), 10_000, 10);
        String code = uniqueCode("REUSE");
        createCoupon(code, "FIXED", 1000, 0, null, 1);
        long first = orderIdOk(uid("u"), orderBody(code, item(productId, 1)));
        assertProblem(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))), 409, "COUPON_EXHAUSTED");

        cancel(first);

        assertThat(statusOf(createOrder(uid("u"), uid("k"), orderBody(code, item(productId, 1))))).isEqualTo(201);
    }
}
