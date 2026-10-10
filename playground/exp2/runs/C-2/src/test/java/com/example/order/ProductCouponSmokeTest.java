package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductCouponSmokeTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R1.1 R1.3 상품 등록(201+Location)과 조회(200)")
    void product_createAndGet() {
        ApiResponse created = createProduct("keyboard", 45000, 10);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).endsWith("/api/products/" + created.id());
        assertThat(created.json().get("reserved").asInt()).isZero();
        assertThat(created.json().get("available").asInt()).isEqualTo(10);

        ApiResponse fetched = getProduct(created.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.text("name")).isEqualTo("keyboard");
        assertThat(fetched.longValue("price")).isEqualTo(45000);
    }

    @Test
    @DisplayName("R1.3 R11.3 없는 상품 조회는 404 PRODUCT_NOT_FOUND")
    void product_get_unknown_returns404ProblemDetail() {
        ApiResponse r = getProduct(999_999_999L);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.2 R11.1 R11.2 상품 검증 실패는 problem+json 400 (type/title/status/detail/code)")
    void product_create_invalid_returns400ProblemDetail() {
        ApiResponse r = createProduct("bad", 0, 10);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(r.text("title")).isNotBlank();
        assertThat(r.text("detail")).isNotBlank();
        assertThat(r.json().get("status").asInt()).isEqualTo(400);
        assertThat(r.text("type")).isNotBlank();
    }

    @Test
    @DisplayName("R11.1 R11.3 깨진 JSON 본문은 problem+json VALIDATION_ERROR")
    void product_create_malformedJson_returns400() {
        ApiResponse r = postProduct("{\"name\":");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 R11.1 필드 타입 불일치는 400")
    void product_create_wrongTypes_return400() {
        assertThat(postProduct("{\"name\":\"a\",\"price\":\"100\",\"stock\":1}").status()).isEqualTo(400);
        assertThat(postProduct("{\"name\":\"a\",\"price\":100.5,\"stock\":1}").status()).isEqualTo(400);
        assertThat(postProduct("{\"name\":123,\"price\":100,\"stock\":1}").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.2 C1 int 범위를 넘는 price는 오버플로 없이 400")
    void product_price_beyondIntRange_isRejectedAsLong() {
        // price 상한은 10,000,000 이므로 int 범위 밖 값은 400 (오버플로 없이)
        assertThat(createProduct("big", 3_000_000_000L, 1).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R2.1 R2.3 쿠폰 등록(201+Location)과 조회")
    void coupon_createAndGet() {
        String code = uniqueCouponCode();
        ApiResponse created = postCoupon(couponJson(code, "RATE", 10, 10000, 5000L, 100,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).endsWith("/api/coupons/" + code);
        assertThat(created.json().get("usedCount").asInt()).isZero();

        ApiResponse fetched = getCoupon(code);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.text("type")).isEqualTo("RATE");
        assertThat(fetched.longValue("maxDiscountAmount")).isEqualTo(5000);
        assertThat(Instant.parse(fetched.text("validFrom"))).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("R2.2 R2.3 maxDiscountAmount 생략은 null로 직렬화")
    void coupon_noMaxDiscount_serializesNull() {
        String code = newCoupon("FIXED", 1000, 0, null, 5);

        ApiResponse fetched = getCoupon(code);
        assertThat(fetched.json().has("maxDiscountAmount")).isTrue();
        assertThat(fetched.json().get("maxDiscountAmount").isNull()).isTrue();
        assertThat(fetched.longValue("minOrderAmount")).isZero();
    }

    @Test
    @DisplayName("R2.2 중복 code는 409 DUPLICATE_COUPON_CODE")
    void coupon_duplicateCode_returns409() {
        String code = newCoupon("FIXED", 1000, 0, null, 5);

        ApiResponse again = postCoupon(couponJson(code, "FIXED", 1000, 0, null, 5));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("R2.2 쿠폰 검증 실패는 400")
    void coupon_invalid_returns400() {
        // RATE 는 100 초과 불가
        assertThat(postCoupon(couponJson(uniqueCouponCode(), "RATE", 101, 0, null, 5)).status()).isEqualTo(400);
        // 소문자 코드
        assertThat(postCoupon(couponJson("abcd", "FIXED", 100, 0, null, 5)).status()).isEqualTo(400);
        // validFrom >= validUntil
        Instant now = Instant.now();
        assertThat(postCoupon(couponJson(uniqueCouponCode(), "FIXED", 100, 0, null, 5, now, now)).status()).isEqualTo(400);
        // 오프셋 없는 시각
        assertThat(postCoupon("{\"code\":\"" + uniqueCouponCode() + "\",\"type\":\"FIXED\",\"value\":100,"
                + "\"totalQuantity\":5,\"validFrom\":\"2026-01-01T00:00:00\",\"validUntil\":\"2027-01-01T00:00:00\"}")
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R2.3 없는 쿠폰 조회는 404 COUPON_NOT_FOUND")
    void coupon_get_unknown_returns404() {
        ApiResponse r = getCoupon("NOPE0000");

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("COUPON_NOT_FOUND");
    }
}
