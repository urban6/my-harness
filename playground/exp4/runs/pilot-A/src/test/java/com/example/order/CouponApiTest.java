package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R2.1~R2.3. 쿠폰 등록·조회 */
class CouponApiTest extends IntegrationTestBase {

    private Map<String, Object> valid(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("type", "RATE");
        m.put("value", 10);
        m.put("minOrderAmount", 1000);
        m.put("maxDiscountAmount", 5000);
        m.put("totalQuantity", 3);
        m.put("validFrom", "2026-01-01T00:00:00+09:00");
        m.put("validUntil", "2099-01-01T00:00:00Z");
        return m;
    }

    @Test
    @DisplayName("R2.1 등록하면 201 + Location + 본문(usedCount=0), 시각은 오프셋 포함 ISO-8601")
    void createReturns201() {
        String code = couponCode();
        Res r = post("/api/coupons", valid(code));

        assertThat(r.status()).isEqualTo(201);
        JsonNode json = r.json();
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("type").asText()).isEqualTo("RATE");
        assertThat(json.get("value").asLong()).isEqualTo(10);
        assertThat(json.get("minOrderAmount").asLong()).isEqualTo(1000);
        assertThat(json.get("maxDiscountAmount").asLong()).isEqualTo(5000);
        assertThat(json.get("totalQuantity").asLong()).isEqualTo(3);
        assertThat(json.get("usedCount").asLong()).isZero();
        assertThat(OffsetDateTime.parse(json.get("validFrom").asText()).toInstant())
                .isEqualTo(Instant.parse("2025-12-31T15:00:00Z"));
        assertThat(OffsetDateTime.parse(json.get("validUntil").asText()).toInstant())
                .isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        assertThat(r.header("Location")).endsWith("/api/coupons/" + code);
    }

    @Test
    @DisplayName("R2.2 minOrderAmount·maxDiscountAmount 생략 가능(0, 제한 없음)")
    void optionalFieldsDefault() {
        Map<String, Object> body = valid(couponCode());
        body.remove("minOrderAmount");
        body.remove("maxDiscountAmount");

        Res r = post("/api/coupons", body);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("minOrderAmount").asLong()).isZero();
        assertThat(r.json().get("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("R2.2 FIXED value는 int 범위를 넘을 수 있다")
    void fixedValueBeyondInt() {
        Map<String, Object> body = valid(couponCode());
        body.put("type", "FIXED");
        body.put("value", 5_000_000_000L);

        Res r = post("/api/coupons", body);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("value").asLong()).isEqualTo(5_000_000_000L);
    }

    @Test
    @DisplayName("R2.2 검증 위반은 400")
    void invalidBodiesRejected() {
        Object[][] cases = {
                {"code", null}, {"code", "ABC"}, {"code", "A".repeat(21)}, {"code", "abcd1234"}, {"code", "AB-CD"},
                {"type", null}, {"type", "PERCENT"}, {"type", "rate"},
                {"value", null}, {"value", 0}, {"value", 101}, {"value", -1}, {"value", 10.5},
                {"minOrderAmount", -1},
                {"maxDiscountAmount", 0}, {"maxDiscountAmount", -3},
                {"totalQuantity", 0}, {"totalQuantity", null},
                {"validFrom", null}, {"validUntil", null},
                {"validFrom", "2026-01-01T00:00:00"}, {"validFrom", "yesterday"},
                {"validUntil", "2026-01-01T00:00:00+09:00"}, // validFrom과 같은 시각
                {"validUntil", "2020-01-01T00:00:00Z"},
        };
        for (Object[] c : cases) {
            Map<String, Object> body = valid(couponCode());
            body.put((String) c[0], c[1]);
            Res r = post("/api/coupons", body);
            assertThat(r.status()).as("case %s=%s", c[0], c[1]).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R2.2 FIXED는 value 100 초과 허용, RATE는 1~100")
    void valueRangeByType() {
        Map<String, Object> fixed = valid(couponCode());
        fixed.put("type", "FIXED");
        fixed.put("value", 3000);
        assertThat(post("/api/coupons", fixed).status()).isEqualTo(201);

        Map<String, Object> rate = valid(couponCode());
        rate.put("value", 100);
        assertThat(post("/api/coupons", rate).status()).isEqualTo(201);
        rate = valid(couponCode());
        rate.put("value", 1);
        assertThat(post("/api/coupons", rate).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R2.2 중복 code는 409 DUPLICATE_COUPON_CODE")
    void duplicateCode() {
        String code = couponCode();
        assertThat(post("/api/coupons", valid(code)).status()).isEqualTo(201);

        Res again = post("/api/coupons", valid(code));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.3 조회 / 없으면 404 COUPON_NOT_FOUND")
    void getCoupon() {
        String code = couponCode();
        post("/api/coupons", valid(code));

        Res found = get("/api/coupons/" + code);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json().get("code").asText()).isEqualTo(code);
        assertThat(found.json().get("usedCount").asLong()).isZero();

        Res missing = get("/api/coupons/NOSUCHCODE");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("COUPON_NOT_FOUND");
    }
}
