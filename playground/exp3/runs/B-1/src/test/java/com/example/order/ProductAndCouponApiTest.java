package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ProductAndCouponApiTest extends IntegrationTest {

    @Test
    void createProduct_returns201_withLocation_andAvailable() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"키보드\",\"price\":10000,\"stock\":5}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/products/\\d+")))
                .andExpect(jsonPath("$.name").value("키보드"))
                .andExpect(jsonPath("$.stock").value(5))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(5));
    }

    @Test
    void createProduct_returns400_problemDetail_whenInvalid() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"price\":-1,\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void createProduct_returns400_whenPriceHasFraction() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"price\":1000.5,\"stock\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProduct_returns404_problemDetail_whenMissing() throws Exception {
        mvc.perform(get("/api/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createAndGetCoupon_roundTrip_withUsedCountZero() throws Exception {
        String code = createCoupon("RATE", 10, 5000, 3000L, 100);

        JsonNode c = coupon(code);

        assertThat(c.get("type").asText()).isEqualTo("RATE");
        assertThat(c.get("value").asLong()).isEqualTo(10);
        assertThat(c.get("minOrderAmount").asLong()).isEqualTo(5000);
        assertThat(c.get("maxDiscountAmount").asLong()).isEqualTo(3000);
        assertThat(c.get("totalQuantity").asInt()).isEqualTo(100);
        assertThat(c.get("usedCount").asInt()).isZero();
    }

    @Test
    void createCoupon_returns201_withLocation() throws Exception {
        String body = """
                {"code":"LOC-1","type":"FIXED","value":1000,"minOrderAmount":0,"maxDiscountAmount":null,
                 "totalQuantity":10,"validFrom":"%s","validUntil":"%s"}
                """.formatted(clock.instant().minus(Duration.ofDays(1)), clock.instant().plus(Duration.ofDays(1)));
        mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/coupons/LOC-1")));
    }

    @Test
    void createCoupon_returns409_whenCodeDuplicated() throws Exception {
        String code = createCoupon("FIXED", 1000, 0, null, 10);
        String body = """
                {"code":"%s","type":"FIXED","value":1000,"minOrderAmount":0,"totalQuantity":10,
                 "validFrom":"%s","validUntil":"%s"}
                """.formatted(code, clock.instant().minus(Duration.ofDays(1)), clock.instant().plus(Duration.ofDays(1)));
        mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void createCoupon_returns400_whenRateOver100_orPeriodReversed() throws Exception {
        String rate = """
                {"code":"BAD-1","type":"RATE","value":150,"minOrderAmount":0,"totalQuantity":10,
                 "validFrom":"2030-01-01T00:00:00Z","validUntil":"2031-01-01T00:00:00Z"}
                """;
        mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(rate))
                .andExpect(status().isBadRequest());

        String period = """
                {"code":"BAD-2","type":"FIXED","value":100,"minOrderAmount":0,"totalQuantity":10,
                 "validFrom":"2031-01-01T00:00:00Z","validUntil":"2030-01-01T00:00:00Z"}
                """;
        mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(period))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCoupon_returns404_whenMissing() throws Exception {
        mvc.perform(get("/api/coupons/NOPE")).andExpect(status().isNotFound());
    }
}
