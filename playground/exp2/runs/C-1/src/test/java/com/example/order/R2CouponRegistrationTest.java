package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import java.util.stream.Stream;

@DisplayName("R2.1~R2.3 쿠폰 등록·조회")
class R2CouponRegistrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("R2.1 등록하면 201 + Location, 본문은 GET 과 같은 형태이고 usedCount=0")
    void r2_1_create() {
        Map<String, Object> body = couponBody("WELCOME10", "RATE", 10);
        body.put("minOrderAmount", 10000);
        body.put("maxDiscountAmount", 5000);
        body.put("totalQuantity", 100);

        ResponseEntity<JsonNode> res = post("/api/coupons", body);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/coupons/WELCOME10");
        JsonNode b = res.getBody();
        assertThat(b.get("code").asText()).isEqualTo("WELCOME10");
        assertThat(b.get("type").asText()).isEqualTo("RATE");
        assertThat(b.get("value").asLong()).isEqualTo(10);
        assertThat(b.get("minOrderAmount").asLong()).isEqualTo(10000);
        assertThat(b.get("maxDiscountAmount").asLong()).isEqualTo(5000);
        assertThat(b.get("totalQuantity").asInt()).isEqualTo(100);
        assertThat(b.get("usedCount").asInt()).isZero();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "code", "type", "value", "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount",
                "validFrom", "validUntil");
    }

    @Test
    @DisplayName("R2.1 Location 으로 조회한 본문이 등록 응답 본문과 같다")
    void r2_1_locationRoundTrip() {
        ResponseEntity<JsonNode> res = post("/api/coupons", couponBody("ROUNDTRIP", "FIXED", 1000));

        ResponseEntity<JsonNode> fetched = get(res.getHeaders().getLocation().toString());

        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).isEqualTo(res.getBody());
    }

    @Test
    @DisplayName("R2.2 minOrderAmount 생략 시 0, maxDiscountAmount 생략 시 null(제한 없음)")
    void r2_2_defaults() {
        Map<String, Object> body = couponBody("DEFAULTS", "FIXED", 1000);
        body.remove("minOrderAmount");

        JsonNode b = createCoupon(body);

        assertThat(b.get("minOrderAmount").asLong()).isZero();
        assertThat(b.get("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.2 경계값(code 4자/20자, RATE 1/100, FIXED 1, totalQuantity 1, max 1)은 허용")
    void r2_2_boundariesAccepted() {
        createCoupon("ABCD", "FIXED", 1);
        createCoupon("A".repeat(20), "RATE", 100);
        createCoupon("RATE1PCT", "RATE", 1);
        Map<String, Object> b = couponBody("ONEONLY1", "FIXED", 1);
        b.put("totalQuantity", 1);
        b.put("maxDiscountAmount", 1);
        b.put("minOrderAmount", 0);
        createCoupon(b);
    }

    @Test
    @DisplayName("R2.2 FIXED 는 100 을 넘는 value 허용")
    void r2_2_fixedValueMayExceed100() {
        JsonNode b = createCoupon("BIGFIXED", "FIXED", 5_000_000_000L);

        assertThat(b.get("value").asLong()).isEqualTo(5_000_000_000L);
    }

    @Test
    @DisplayName("R2.2/C2 +09:00 오프셋 입력도 받고, 응답 시각은 같은 순간의 오프셋 포함 ISO-8601 이다")
    void r2_2_offsetInputAccepted() {
        Map<String, Object> body = couponBody("OFFSET01", "FIXED", 1000);
        body.put("validFrom", "2030-01-01T00:00:00+09:00");
        body.put("validUntil", "2031-01-01T00:00:00+09:00");

        JsonNode b = createCoupon(body);

        assertThat(OffsetDateTime.parse(b.get("validFrom").asText()).toInstant())
                .isEqualTo(Instant.parse("2029-12-31T15:00:00Z"));
        assertThat(OffsetDateTime.parse(b.get("validUntil").asText()).toInstant())
                .isEqualTo(Instant.parse("2030-12-31T15:00:00Z"));
    }

    static Stream<Arguments> invalidCoupons() {
        return Stream.of(
                invalid("code 누락", m -> m.remove("code")),
                invalid("code 3자", m -> m.put("code", "ABC")),
                invalid("code 21자", m -> m.put("code", "A".repeat(21))),
                invalid("code 소문자", m -> m.put("code", "abcd1234")),
                invalid("code 특수문자", m -> m.put("code", "AB-CD")),
                invalid("code 공백 포함", m -> m.put("code", "AB CD")),
                invalid("type 누락", m -> m.remove("type")),
                invalid("type 미정의 값", m -> m.put("type", "PERCENT")),
                invalid("value 누락", m -> m.remove("value")),
                invalid("FIXED value 0", m -> m.put("value", 0)),
                invalid("FIXED value 음수", m -> m.put("value", -5)),
                invalid("RATE value 0", m -> {
                    m.put("type", "RATE");
                    m.put("value", 0);
                }),
                invalid("RATE value 101", m -> {
                    m.put("type", "RATE");
                    m.put("value", 101);
                }),
                invalid("minOrderAmount 음수", m -> m.put("minOrderAmount", -1)),
                invalid("maxDiscountAmount 0", m -> m.put("maxDiscountAmount", 0)),
                invalid("maxDiscountAmount 음수", m -> m.put("maxDiscountAmount", -10)),
                invalid("totalQuantity 누락", m -> m.remove("totalQuantity")),
                invalid("totalQuantity 0", m -> m.put("totalQuantity", 0)),
                invalid("validFrom 누락", m -> m.remove("validFrom")),
                invalid("validUntil 누락", m -> m.remove("validUntil")),
                invalid("validFrom == validUntil", m -> {
                    m.put("validFrom", "2030-01-01T00:00:00Z");
                    m.put("validUntil", "2030-01-01T00:00:00Z");
                }),
                invalid("validFrom > validUntil", m -> {
                    m.put("validFrom", "2031-01-01T00:00:00Z");
                    m.put("validUntil", "2030-01-01T00:00:00Z");
                }),
                invalid("validFrom 형식 오류", m -> m.put("validFrom", "yesterday")));
    }

    private static Arguments invalid(String label, Consumer<Map<String, Object>> mutator) {
        return Arguments.of(label, mutator);
    }

    @ParameterizedTest(name = "R2.2 {0} -> 400")
    @MethodSource("invalidCoupons")
    void r2_2_invalidReturns400(String label, Consumer<Map<String, Object>> mutator) {
        Map<String, Object> body = couponBody("VALID001", "FIXED", 1000);
        mutator.accept(body);

        ResponseEntity<JsonNode> res = post("/api/coupons", body);

        assertProblem(res, 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coupons", Long.class)).isZero();
    }

    @Test
    @DisplayName("R2.2 이미 있는 code 로 등록하면 409 DUPLICATE_COUPON_CODE, 기존 쿠폰은 그대로")
    void r2_2_duplicateCode() {
        createCoupon("DUPCODE1", "FIXED", 1000);
        Map<String, Object> other = couponBody("DUPCODE1", "RATE", 50);

        ResponseEntity<JsonNode> res = post("/api/coupons", other);

        assertProblem(res, 409, "DUPLICATE_COUPON_CODE");
        JsonNode existing = getCoupon("DUPCODE1");
        assertThat(existing.get("type").asText()).isEqualTo("FIXED");
        assertThat(existing.get("value").asLong()).isEqualTo(1000);
    }

    @Test
    @DisplayName("R2.2 중복 code 라도 본문이 위반이면 400 이 먼저다")
    void r2_2_validationBeforeDuplicate() {
        createCoupon("DUPCODE2", "FIXED", 1000);
        Map<String, Object> other = couponBody("DUPCODE2", "RATE", 500);

        assertProblem(post("/api/coupons", other), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R2.3 조회는 200 + 모든 필드, 없으면 404 COUPON_NOT_FOUND")
    void r2_3_getAndNotFound() {
        createCoupon("GETME001", "FIXED", 2500);

        JsonNode b = getCoupon("GETME001");

        assertThat(b.get("code").asText()).isEqualTo("GETME001");
        assertThat(b.get("value").asLong()).isEqualTo(2500);
        assertThat(b.get("usedCount").asInt()).isZero();
        assertProblem(get("/api/coupons/NOSUCH01"), 404, "COUPON_NOT_FOUND");
    }
}
