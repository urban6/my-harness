package com.example.order.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 컨텍스트 로드(Flyway V1 + ddl-auto validate 포함) + 상품·쿠폰 생성/조회 해피패스. */
class ProductCouponSmokeTest extends IntegrationTestBase {

    @Test
    void contextLoads_andProductCanBeCreatedAndFetched() {
        ResponseEntity<String> created = post("/api/products", Map.of("name", "keyboard", "price", 50_000, "stock", 10));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = json(created).get("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/products/" + id);
        assertThat(json(created).get("reserved").asInt()).isZero();
        assertThat(json(created).get("available").asInt()).isEqualTo(10);

        ResponseEntity<String> fetched = getProduct(id);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(fetched).get("name").asText()).isEqualTo("keyboard");
        assertThat(json(fetched).get("price").asLong()).isEqualTo(50_000L);
        assertThat(json(fetched).get("stock").asInt()).isEqualTo(10);
    }

    @Test
    void couponCanBeCreatedAndFetched_andErrorsArriveAsProblemJson() {
        ResponseEntity<String> created = createCouponResponse("SMOKE10", "RATE", 10, 0, 5_000L, 3);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/coupons/SMOKE10");
        assertThat(json(created).get("usedCount").asLong()).isZero();

        ResponseEntity<String> fetched = getCoupon("SMOKE10");
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(fetched).get("maxDiscountAmount").asLong()).isEqualTo(5_000L);

        ResponseEntity<String> missing = getProduct(999_999L);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(missing).get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }
}
