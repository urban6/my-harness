package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R2.1 ~ R2.3. 쿠폰 등록·조회·검증 (할인 계산 R2.4 이후는 R02CouponDiscountTest). */
class R02CouponTest extends AbstractIntegrationTest {

    private static final String FROM = "\"2020-01-01T00:00:00Z\"";
    private static final String UNTIL = "\"2099-01-01T00:00:00Z\"";

    /** 각 인자는 JSON 조각 그대로(문자열이면 따옴표 포함). null이면 필드를 생략한다. */
    private static String body(String code, String type, String value, String min, String max, String total,
            String from, String until) {
        StringBuilder sb = new StringBuilder("{");
        String[][] fields = {{"code", code}, {"type", type}, {"value", value}, {"minOrderAmount", min},
                {"maxDiscountAmount", max}, {"totalQuantity", total}, {"validFrom", from}, {"validUntil", until}};
        String sep = "";
        for (String[] f : fields) {
            if (f[1] != null) {
                sb.append(sep).append('"').append(f[0]).append("\":").append(f[1]);
                sep = ",";
            }
        }
        return sb.append("}").toString();
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static String validBody(String code) {
        return body(q(code), q("RATE"), "10", "10000", "5000", "100", FROM, UNTIL);
    }

    @Test
    @DisplayName("R2.1 쿠폰 등록은 201 + Location, 본문은 R2.3 형태이고 usedCount=0이다")
    void r2_1_create_returnsCreatedWithLocationAndBody() {
        String code = uniqueCouponCode();

        ApiResponse r = postCoupon(body(q(code), q("RATE"), "10", "10000", "5000", "100",
                q("2026-01-01T00:00:00Z"), q("2099-01-01T00:00:00Z")));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).endsWith("/api/coupons/" + code);
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("code", "type", "value",
                "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount", "validFrom", "validUntil");
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.text("type")).isEqualTo("RATE");
        assertThat(r.longValue("value")).isEqualTo(10);
        assertThat(r.longValue("minOrderAmount")).isEqualTo(10_000);
        assertThat(r.longValue("maxDiscountAmount")).isEqualTo(5_000);
        assertThat(r.longValue("totalQuantity")).isEqualTo(100);
        assertThat(r.longValue("usedCount")).isZero();
    }

    @Test
    @DisplayName("R2.1 등록 응답 본문은 R2.3 조회 본문과 같다")
    void r2_1_createBody_equalsGetBody() {
        String code = uniqueCouponCode();
        ApiResponse created = postCoupon(validBody(code));

        assertThat(getCoupon(code).json()).isEqualTo(created.json());
    }

    @Test
    @DisplayName("R2.2 minOrderAmount를 생략하면 0, maxDiscountAmount를 생략하면 null(제한 없음)이다")
    void r2_2_optionalFields_defaults() {
        String code = uniqueCouponCode();

        ApiResponse r = postCoupon(body(q(code), q("FIXED"), "1000", null, null, "5", FROM, UNTIL));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.longValue("minOrderAmount")).isZero();
        assertThat(r.json().has("maxDiscountAmount")).isTrue();
        assertThat(r.json().get("maxDiscountAmount").isNull()).isTrue();
    }

    @ParameterizedTest(name = "R2.2 허용 경계 {0} -> 201")
    @MethodSource("validBoundaries")
    @DisplayName("R2.2 경계값 이내의 요청은 201이다")
    void r2_2_validBoundaries_areAccepted(String label, String body) {
        ApiResponse r = postCoupon(body);

        assertThat(r.status()).as("%s: %s", label, r).isEqualTo(201);
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(
                arguments("code 4자", body(q(shortCode(4)), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 20자", body(q(shortCode(20)), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 숫자만", body(q(digitsOnly()), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("FIXED value 1", body(q(shortCode(10)), q("FIXED"), "1", "0", "1", "1", FROM, UNTIL)),
                arguments("FIXED value 10,000,000,000(int 초과)",
                        body(q(shortCode(10)), q("FIXED"), "10000000000", "0", null, "1", FROM, UNTIL)),
                arguments("RATE value 1", body(q(shortCode(10)), q("RATE"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("RATE value 100", body(q(shortCode(10)), q("RATE"), "100", "0", null, "1", FROM, UNTIL)),
                arguments("minOrderAmount 0", body(q(shortCode(10)), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("minOrderAmount int 초과", body(q(shortCode(10)), q("FIXED"), "1", "5000000000", "4000000000", "1", FROM, UNTIL)),
                arguments("maxDiscountAmount 1", body(q(shortCode(10)), q("RATE"), "5", "0", "1", "1", FROM, UNTIL)),
                arguments("totalQuantity 1", body(q(shortCode(10)), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("validFrom +09:00 오프셋", body(q(shortCode(10)), q("FIXED"), "1", "0", null, "1",
                        q("2020-01-01T09:00:00+09:00"), UNTIL)),
                arguments("validUntil이 validFrom보다 1초 뒤", body(q(shortCode(10)), q("FIXED"), "1", "0", null, "1",
                        q("2030-01-01T00:00:00Z"), q("2030-01-01T00:00:01Z"))));
    }

    @ParameterizedTest(name = "R2.2 위반 {0} -> 400")
    @MethodSource("invalidBodies")
    @DisplayName("R2.2 필드 위반은 400 VALIDATION_ERROR이다")
    void r2_2_violations_return400(String label, String body) {
        ApiResponse r = postCoupon(body);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    static Stream<Arguments> invalidBodies() {
        String ok = q("ABCD1234");
        return Stream.of(
                arguments("code 누락", body(null, q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 소문자", body(q("abcd1234"), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 대소문자 혼합", body(q("ABcd1234"), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 3자", body(q("ABC"), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 21자", body(q("A".repeat(21)), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 특수문자", body(q("AB-CD123"), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 공백 포함", body(q("AB CD123"), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("code 빈 문자열", body(q(""), q("FIXED"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("type 누락", body(ok, null, "1", "0", null, "1", FROM, UNTIL)),
                arguments("type 알 수 없는 값", body(ok, q("PERCENT"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("type 소문자", body(ok, q("fixed"), "1", "0", null, "1", FROM, UNTIL)),
                arguments("value 누락", body(ok, q("FIXED"), null, "0", null, "1", FROM, UNTIL)),
                arguments("FIXED value 0", body(ok, q("FIXED"), "0", "0", null, "1", FROM, UNTIL)),
                arguments("FIXED value -1", body(ok, q("FIXED"), "-1", "0", null, "1", FROM, UNTIL)),
                arguments("RATE value 0", body(ok, q("RATE"), "0", "0", null, "1", FROM, UNTIL)),
                arguments("RATE value 101", body(ok, q("RATE"), "101", "0", null, "1", FROM, UNTIL)),
                arguments("minOrderAmount -1", body(ok, q("FIXED"), "1", "-1", null, "1", FROM, UNTIL)),
                arguments("maxDiscountAmount 0", body(ok, q("FIXED"), "1", "0", "0", "1", FROM, UNTIL)),
                arguments("maxDiscountAmount -1", body(ok, q("FIXED"), "1", "0", "-1", "1", FROM, UNTIL)),
                arguments("totalQuantity 누락", body(ok, q("FIXED"), "1", "0", null, null, FROM, UNTIL)),
                arguments("totalQuantity 0", body(ok, q("FIXED"), "1", "0", null, "0", FROM, UNTIL)),
                arguments("totalQuantity -1", body(ok, q("FIXED"), "1", "0", null, "-1", FROM, UNTIL)),
                arguments("validFrom 누락", body(ok, q("FIXED"), "1", "0", null, "1", null, UNTIL)),
                arguments("validUntil 누락", body(ok, q("FIXED"), "1", "0", null, "1", FROM, null)),
                arguments("validFrom == validUntil", body(ok, q("FIXED"), "1", "0", null, "1",
                        q("2030-01-01T00:00:00Z"), q("2030-01-01T00:00:00Z"))),
                arguments("validFrom > validUntil", body(ok, q("FIXED"), "1", "0", null, "1",
                        q("2030-01-01T00:00:01Z"), q("2030-01-01T00:00:00Z"))),
                arguments("오프셋이 다른 같은 시점", body(ok, q("FIXED"), "1", "0", null, "1",
                        q("2030-01-01T09:00:00+09:00"), q("2030-01-01T00:00:00Z"))),
                arguments("오프셋 없는 시각", body(ok, q("FIXED"), "1", "0", null, "1",
                        q("2030-01-01T00:00:00"), UNTIL)),
                arguments("해석할 수 없는 시각", body(ok, q("FIXED"), "1", "0", null, "1", q("yesterday"), UNTIL)),
                arguments("value가 문자열", body(ok, q("FIXED"), q("100"), "0", null, "1", FROM, UNTIL)),
                arguments("value가 소수", body(ok, q("FIXED"), "100.5", "0", null, "1", FROM, UNTIL)));
    }

    @Test
    @DisplayName("R2.2 이미 있는 code로 등록하면 409 DUPLICATE_COUPON_CODE이고 기존 쿠폰은 그대로다")
    void r2_2_duplicateCode_returns409_andKeepsOriginal() {
        String code = newCoupon("FIXED", 1_000, 0, null, 5);

        ApiResponse again = postCoupon(couponJson(code, "RATE", 50, 777, 888L, 99));

        assertProblem(again, 409, "DUPLICATE_COUPON_CODE");
        ApiResponse original = getCoupon(code);
        assertThat(original.text("type")).isEqualTo("FIXED");
        assertThat(original.longValue("value")).isEqualTo(1_000);
        assertThat(original.longValue("totalQuantity")).isEqualTo(5);
    }

    @Test
    @DisplayName("R2.2 같은 code를 동시에 등록하면 정확히 한 건만 201이고 나머지는 409 DUPLICATE_COUPON_CODE이다")
    void r2_2_duplicateCode_concurrent_exactlyOneCreated() {
        String code = uniqueCouponCode();

        var results = runConcurrently(8, i -> postCoupon(couponJson(code, "FIXED", 100, 0, null, 3)));

        assertThat(countStatus(results, 201)).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(7);
        assertThat(results.stream().filter(r -> r.status() == 409).map(ApiResponse::code))
                .allMatch("DUPLICATE_COUPON_CODE"::equals);
    }

    @Test
    @DisplayName("R2.3 C2 쿠폰 조회는 200이고 시각은 오프셋이 포함된 ISO-8601이며 같은 시점이다")
    void r2_3_get_returnsAllFields_withOffsetTimestamps() {
        String code = uniqueCouponCode();
        postCoupon(body(q(code), q("FIXED"), "3000", "10000", null, "7",
                q("2026-01-01T09:00:00+09:00"), q("2099-01-01T00:00:00Z")));

        ApiResponse r = getCoupon(code);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.text("type")).isEqualTo("FIXED");
        assertThat(r.longValue("value")).isEqualTo(3_000);
        assertThat(r.longValue("minOrderAmount")).isEqualTo(10_000);
        assertThat(r.longValue("totalQuantity")).isEqualTo(7);
        assertThat(r.longValue("usedCount")).isZero();
        assertThat(r.text("validFrom")).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(r.text("validUntil")).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(instant(r, "validFrom")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(instant(r, "validUntil")).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰 조회는 404 COUPON_NOT_FOUND이다")
    void r2_3_get_unknown_returns404() {
        assertProblem(getCoupon("NOSUCH12"), 404, "COUPON_NOT_FOUND");
    }

    // ---- 코드 생성 보조 ----

    private static String shortCode(int length) {
        String base = uniqueCouponCode().replaceAll("[^A-Z0-9]", "X");
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < length) {
            sb.append(base);
        }
        return sb.substring(sb.length() - length);
    }

    private static String digitsOnly() {
        return String.format("%010d", (System.nanoTime() & Long.MAX_VALUE) % 10_000_000_000L);
    }
}
