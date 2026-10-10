package com.example.order.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R2.1 ~ R2.3. 쿠폰 등록·검증·중복·조회. */
class CouponApiTest extends IntegrationTestBase {

    private ResponseEntity<JsonNode> register(Consumer<Map<String, Object>> customizer) {
        Map<String, Object> body = couponBody(uniqueCode(), "FIXED", 1000);
        customizer.accept(body);
        return post("/api/coupons", body);
    }

    private void assertRejected(Consumer<Map<String, Object>> customizer) {
        assertProblem(register(customizer), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.1 쿠폰을 등록하면 201 + Location + usedCount=0 본문")
    void r2_1_registerReturns201WithLocationAndBody() {
        String code = uniqueCode();
        Map<String, Object> body = couponBody(code, "RATE", 15);
        body.put("minOrderAmount", 5000);
        body.put("maxDiscountAmount", 3000);
        body.put("totalQuantity", 7);

        ResponseEntity<JsonNode> res = post("/api/coupons", body);

        assertStatus(res, 201);
        assertThat(res.getHeaders().getLocation()).isNotNull();
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/coupons/" + code);
        JsonNode b = res.getBody();
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("type").asText()).isEqualTo("RATE");
        assertThat(b.get("value").asLong()).isEqualTo(15);
        assertThat(b.get("minOrderAmount").asLong()).isEqualTo(5000);
        assertThat(b.get("maxDiscountAmount").asLong()).isEqualTo(3000);
        assertThat(b.get("totalQuantity").asInt()).isEqualTo(7);
        assertThat(b.get("usedCount").asInt()).isZero();
        assertThat(time(b, "validFrom")).isEqualTo(OffsetDateTime.parse((String) body.get("validFrom")));
        assertThat(time(b, "validUntil")).isEqualTo(OffsetDateTime.parse((String) body.get("validUntil")));
    }

    @Test
    @DisplayName("R2.1 등록 응답 본문은 GET 조회(R2.3)와 같다")
    void r2_1_registerBodyEqualsGetBody() {
        JsonNode created = createCoupon(couponBody(uniqueCode(), "FIXED", 500));

        assertThat(get("/api/coupons/" + created.get("code").asText()).getBody()).isEqualTo(created);
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 생략 시 0, maxDiscountAmount 생략 시 null(제한 없음)")
    void r2_2_omittedOptionalFieldsDefault() {
        ResponseEntity<JsonNode> res = register(b -> {
        });

        assertStatus(res, 201);
        assertThat(res.getBody().get("minOrderAmount").asLong()).isZero();
        assertThat(res.getBody().has("maxDiscountAmount")).isTrue();
        assertThat(res.getBody().get("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.2 minOrderAmount/maxDiscountAmount 를 null 로 보내도 생략과 같다")
    void r2_2_explicitNullOptionalFieldsDefault() {
        ResponseEntity<JsonNode> res = register(b -> {
            b.put("minOrderAmount", null);
            b.put("maxDiscountAmount", null);
        });

        assertStatus(res, 201);
        assertThat(res.getBody().get("minOrderAmount").asLong()).isZero();
        assertThat(res.getBody().get("maxDiscountAmount").isNull()).isTrue();
    }

    @ParameterizedTest(name = "R2.2 code={0} 은 201 (4~20자 대문자·숫자)")
    @ValueSource(strings = {"ABCD", "A1B2", "1234", "ABCDEFGHIJ0123456789"})
    void r2_2_validCodesAccepted(String code) {
        ResponseEntity<JsonNode> res = register(b -> b.put("code", code));

        assertStatus(res, 201);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
    }

    @ParameterizedTest(name = "R2.2 code=\"{0}\" 은 400")
    @ValueSource(strings = {"", "ABC", "ABCDEFGHIJ01234567890", "abcd", "ABCd", "AB-CD", "AB CD", "AB_CD", "가나다라"})
    void r2_2_invalidCodesRejected(String code) {
        assertRejected(b -> b.put("code", code));
    }

    @Test
    @DisplayName("R2.2 code 누락은 400")
    void r2_2_missingCodeRejected() {
        assertRejected(b -> b.remove("code"));
    }

    @ParameterizedTest(name = "R2.2 type={0} 은 400")
    @ValueSource(strings = {"fixed", "rate", "PERCENT", "", "Fixed"})
    void r2_2_invalidTypeRejected(String type) {
        assertRejected(b -> b.put("type", type));
    }

    @Test
    @DisplayName("R2.2 type 누락은 400")
    void r2_2_missingTypeRejected() {
        assertRejected(b -> b.remove("type"));
    }

    @ParameterizedTest(name = "R2.2 FIXED value={0} 은 400")
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void r2_2_fixedValueBelowOneRejected(long value) {
        assertRejected(b -> {
            b.put("type", "FIXED");
            b.put("value", value);
        });
    }

    @ParameterizedTest(name = "R2.2 FIXED value={0} 은 201 (상한 없음)")
    @ValueSource(longs = {1, 101, 5_000_000_000L})
    void r2_2_fixedValueAcceptsAnyPositive(long value) {
        ResponseEntity<JsonNode> res = register(b -> b.put("value", value));

        assertStatus(res, 201);
        assertThat(res.getBody().get("value").asLong()).isEqualTo(value);
    }

    @ParameterizedTest(name = "R2.2 RATE value={0} 은 400")
    @ValueSource(longs = {-1, 0, 101, 1000})
    void r2_2_rateValueOutOfRangeRejected(long value) {
        assertRejected(b -> {
            b.put("type", "RATE");
            b.put("value", value);
        });
    }

    @ParameterizedTest(name = "R2.2 RATE value={0} 은 201 (경계 1~100)")
    @ValueSource(longs = {1, 50, 100})
    void r2_2_rateValueBoundariesAccepted(long value) {
        ResponseEntity<JsonNode> res = register(b -> {
            b.put("type", "RATE");
            b.put("value", value);
        });

        assertStatus(res, 201);
    }

    @Test
    @DisplayName("R2.2 value 누락은 400")
    void r2_2_missingValueRejected() {
        assertRejected(b -> b.remove("value"));
    }

    @ParameterizedTest(name = "R2.2 minOrderAmount={0} 은 400")
    @ValueSource(longs = {-1, -5000, Long.MIN_VALUE})
    void r2_2_negativeMinOrderAmountRejected(long min) {
        assertRejected(b -> b.put("minOrderAmount", min));
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 0 은 201")
    void r2_2_zeroMinOrderAmountAccepted() {
        assertStatus(register(b -> b.put("minOrderAmount", 0)), 201);
    }

    @ParameterizedTest(name = "R2.2 maxDiscountAmount={0} 은 400")
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void r2_2_maxDiscountBelowOneRejected(long max) {
        assertRejected(b -> b.put("maxDiscountAmount", max));
    }

    @Test
    @DisplayName("R2.2 maxDiscountAmount 1 은 201")
    void r2_2_maxDiscountOneAccepted() {
        assertStatus(register(b -> b.put("maxDiscountAmount", 1)), 201);
    }

    @ParameterizedTest(name = "R2.2 totalQuantity={0} 은 400")
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0})
    void r2_2_totalQuantityBelowOneRejected(int quantity) {
        assertRejected(b -> b.put("totalQuantity", quantity));
    }

    @Test
    @DisplayName("R2.2 totalQuantity 1 은 201, 누락은 400")
    void r2_2_totalQuantityBoundary() {
        assertStatus(register(b -> b.put("totalQuantity", 1)), 201);
        assertRejected(b -> b.remove("totalQuantity"));
    }

    @Test
    @DisplayName("R2.2 validFrom == validUntil 은 400")
    void r2_2_equalPeriodRejected() {
        String t = Instant.parse("2031-01-01T00:00:00Z").toString();
        assertRejected(b -> {
            b.put("validFrom", t);
            b.put("validUntil", t);
        });
    }

    @Test
    @DisplayName("R2.2 validFrom > validUntil 은 400")
    void r2_2_reversedPeriodRejected() {
        assertRejected(b -> {
            b.put("validFrom", "2031-01-02T00:00:00Z");
            b.put("validUntil", "2031-01-01T00:00:00Z");
        });
    }

    @Test
    @DisplayName("R2.2 validFrom < validUntil 이 1마이크로초 차이여도 201")
    void r2_2_minimalPeriodAccepted() {
        assertStatus(register(b -> {
            b.put("validFrom", "2031-01-01T00:00:00.000000Z");
            b.put("validUntil", "2031-01-01T00:00:00.000001Z");
        }), 201);
    }

    @Test
    @DisplayName("R2.2 validFrom/validUntil 누락은 400")
    void r2_2_missingPeriodRejected() {
        assertRejected(b -> b.remove("validFrom"));
        assertRejected(b -> b.remove("validUntil"));
    }

    @ParameterizedTest(name = "R2.2/C2 오프셋 없는 시각 \"{0}\" 은 400")
    @ValueSource(strings = {"2031-01-01T00:00:00", "2031-01-01", "not-a-date", "1893456000"})
    void c2_timeWithoutOffsetRejected(String bad) {
        assertRejected(b -> b.put("validFrom", bad));
    }

    @Test
    @DisplayName("R2.2/C2 숫자 타임스탬프(JSON number)는 400")
    void c2_numericTimestampRejected() {
        assertRejected(b -> b.put("validFrom", 1893456000));
    }

    @Test
    @DisplayName("C2 +09:00 오프셋 입력은 받아들이고 같은 순간으로 돌려준다")
    void c2_offsetInputAcceptedAndSameInstantReturned() {
        ResponseEntity<JsonNode> res = register(b -> {
            b.put("validFrom", "2031-01-01T09:00:00+09:00");
            b.put("validUntil", "2031-01-02T09:00:00+09:00");
        });

        assertStatus(res, 201);
        assertThat(time(res.getBody(), "validFrom").toInstant()).isEqualTo(Instant.parse("2031-01-01T00:00:00Z"));
        assertThat(time(res.getBody(), "validUntil").toInstant()).isEqualTo(Instant.parse("2031-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("R2.2 형식이 틀린 필드 타입(문자열 value, 소수 totalQuantity)은 400")
    void r2_2_wrongTypesRejected() {
        assertRejected(b -> b.put("value", "1000"));
        assertRejected(b -> b.put("totalQuantity", 1.5));
    }

    @Test
    @DisplayName("R2.2 검증 실패한 요청은 쿠폰을 만들지 않는다")
    void r2_2_rejectedRequestCreatesNothing() {
        String code = uniqueCode();
        Map<String, Object> body = couponBody(code, "RATE", 101);

        assertStatus(post("/api/coupons", body), 400);

        assertProblem(get("/api/coupons/" + code), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.2 이미 있는 code 는 409 DUPLICATE_COUPON_CODE 이고 기존 쿠폰은 바뀌지 않는다")
    void r2_2_duplicateCodeRejected() {
        String code = uniqueCode();
        createCoupon(couponBody(code, "FIXED", 1000));

        Map<String, Object> second = couponBody(code, "RATE", 10);
        second.put("totalQuantity", 5);
        ResponseEntity<JsonNode> res = post("/api/coupons", second);

        assertProblem(res, 409, "DUPLICATE_COUPON_CODE");
        JsonNode stored = coupon(code);
        assertThat(stored.get("type").asText()).isEqualTo("FIXED");
        assertThat(stored.get("value").asLong()).isEqualTo(1000);
        assertThat(stored.get("totalQuantity").asInt()).isEqualTo(1000);
    }

    @Test
    @DisplayName("R2.2 같은 code 를 동시에 등록하면 정확히 1건만 201, 나머지는 409 DUPLICATE_COUPON_CODE")
    void r2_2_concurrentDuplicateRegistration() throws Exception {
        String code = uniqueCode();
        int n = 8;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<ResponseEntity<JsonNode>> task = () -> {
                    ready.countDown();
                    start.await();
                    return post("/api/coupons", couponBody(code, "FIXED", 1000));
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int created = 0;
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                ResponseEntity<JsonNode> res = f.get(60, TimeUnit.SECONDS);
                if (code(res) == 201) {
                    created++;
                } else {
                    assertProblem(res, 409, "DUPLICATE_COUPON_CODE");
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰 조회는 404 COUPON_NOT_FOUND")
    void r2_3_unknownCouponReturns404() {
        assertProblem(get("/api/coupons/NOSUCHCODE1"), 404, "COUPON_NOT_FOUND");
    }

    @Test
    @DisplayName("R2.3 GET 은 문서화된 9개 필드를 담은 200 을 돌려준다")
    void r2_3_getReturnsDocumentedShape() {
        String code = uniqueCode();
        createCoupon(couponBody(code, "FIXED", 1000));

        JsonNode b = get("/api/coupons/" + code).getBody();

        List<String> fields = new ArrayList<>();
        b.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("code", "type", "value", "minOrderAmount", "maxDiscountAmount",
                "totalQuantity", "usedCount", "validFrom", "validUntil");
    }

    @Test
    @DisplayName("C1 int 를 넘는 value/minOrderAmount/maxDiscountAmount 를 수용하고 그대로 돌려준다")
    void c1_couponAmountsBeyondIntRangeAccepted() {
        String code = uniqueCode();
        Map<String, Object> body = couponBody(code, "FIXED", 5_000_000_000L);
        body.put("minOrderAmount", 3_000_000_000L);
        body.put("maxDiscountAmount", 4_000_000_000L);

        assertStatus(post("/api/coupons", body), 201);

        JsonNode b = coupon(code);
        assertThat(b.get("value").asLong()).isEqualTo(5_000_000_000L);
        assertThat(b.get("minOrderAmount").asLong()).isEqualTo(3_000_000_000L);
        assertThat(b.get("maxDiscountAmount").asLong()).isEqualTo(4_000_000_000L);
    }
}
