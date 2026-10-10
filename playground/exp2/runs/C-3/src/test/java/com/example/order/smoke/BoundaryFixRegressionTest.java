package com.example.order.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** boundary-verifier FIX 회귀: 앱 검증으로 막아야 할 입력이 DB에서 500으로 새지 않는지. */
class BoundaryFixRegressionTest extends IntegrationTestBase {

    @Test
    void couponPeriod_equalAfterMicrosTruncation_returns400() {
        String body = """
                {"code":"TRUNC1","type":"FIXED","value":10,"totalQuantity":1,
                 "validFrom":"2026-01-01T00:00:00.0000001Z","validUntil":"2026-01-01T00:00:00.0000009Z"}""";

        ResponseEntity<String> r = post("/api/coupons", body);

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(json(r).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void productName_withNul_returns400() {
        ResponseEntity<String> r = post("/api/products", "{\"name\":\"a\\u0000b\",\"price\":1000,\"stock\":1}");

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(json(r).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void orderCreate_couponCodeWithNul_returns404CouponNotFound() {
        long p = createProduct("nul-p", 1000, 5);

        ResponseEntity<String> r = createOrder("u1", "nul-k1", "{\"items\":[{\"productId\":" + p
                + ",\"quantity\":1}],\"couponCode\":\"A\\u0000B\"}");

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(json(r).get("code").asText()).isEqualTo("COUPON_NOT_FOUND");
        assertThat(json(getProduct(p)).get("reserved").asInt()).isZero();
    }

    @Test
    void orderCreate_missingProductTakesPrecedenceOverNulCouponCode() {
        ResponseEntity<String> r = createOrder("u1", "nul-k2",
                "{\"items\":[{\"productId\":987654321,\"quantity\":1}],\"couponCode\":\"\\u0000\"}");

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(json(r).get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    /**
     * 경로의 %00 은 Tomcat 이 애플리케이션에 도달하기 전에 400 으로 거부한다 (본문은 Tomcat HTML 오류 페이지이며 problem+json 이 아니다).
     * 앱의 CouponService NUL 가드는 경로 변수로는 도달 불가 - 주문 본문의 couponCode(\u0000)로 검증한다(위 테스트).
     * 여기서는 NUL 이 실제로 서버에 전달되는 조건(미리 인코딩된 URI)에서 5xx 가 아니라 400 임을 고정한다.
     */
    @Test
    void couponGet_withNulInPath_isRejectedByContainerWith400_neverA5xx() {
        ResponseEntity<String> r = getRaw("/api/coupons/A%00B");

        assertThat(r.getStatusCode().is5xxServerError()).isFalse();
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void orderList_userIdWithNul_returnsEmptyPage() {
        ResponseEntity<String> r = getRaw("/api/orders?userId=%00");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(json(r).get("content")).isEmpty();
        assertThat(json(r).get("nextCursor").isNull()).isTrue();
    }
}
