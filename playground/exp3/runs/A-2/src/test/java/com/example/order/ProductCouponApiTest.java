package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ProductCouponApiTest extends ApiTestSupport {

    @Test
    void createsAndReadsProduct() throws Exception {
        var res = postJson("/api/products", Map.of("name", "Keyboard", "price", 30000, "stock", 5))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/products/\\d+")))
                .andExpect(jsonPath("$.available").value(5));
        long id = body(res).get("id").asLong();

        mvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.price").value(30000))
                .andExpect(jsonPath("$.stock").value(5))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(5));
    }

    @Test
    void invalidProductIsProblemDetail() throws Exception {
        postJson("/api/products", Map.of("name", "", "price", -1, "stock", 1))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unknownProductIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createsAndReadsCoupon() throws Exception {
        String code = createCoupon("RATE", 10, 10000, 5000L, 100);
        mvc.perform(get("/api/coupons/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("RATE"))
                .andExpect(jsonPath("$.value").value(10))
                .andExpect(jsonPath("$.minOrderAmount").value(10000))
                .andExpect(jsonPath("$.maxDiscountAmount").value(5000))
                .andExpect(jsonPath("$.totalQuantity").value(100))
                .andExpect(jsonPath("$.usedCount").value(0));
    }

    @Test
    void couponCreationReturnsLocationAndRejectsDuplicates() throws Exception {
        String code = "DUP" + uid();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("type", "FIXED");
        payload.put("value", 1000);
        payload.put("minOrderAmount", 0);
        payload.put("maxDiscountAmount", null);
        payload.put("totalQuantity", 3);
        payload.put("validFrom", Instant.now().toString());
        payload.put("validUntil", Instant.now().plus(1, ChronoUnit.DAYS).toString());

        postJson("/api/coupons", payload)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/coupons/" + code)));
        postJson("/api/coupons", payload)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsInvalidCoupons() throws Exception {
        Instant from = Instant.now();
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("code", "BAD" + uid());
        rate.put("type", "RATE");
        rate.put("value", 150);
        rate.put("minOrderAmount", 0);
        rate.put("totalQuantity", 1);
        rate.put("validFrom", from.toString());
        rate.put("validUntil", from.plus(1, ChronoUnit.DAYS).toString());
        postJson("/api/coupons", rate).andExpect(status().isBadRequest());

        rate.put("value", 10);
        rate.put("validUntil", from.minus(1, ChronoUnit.DAYS).toString());
        postJson("/api/coupons", rate).andExpect(status().isBadRequest());

        rate.put("validUntil", from.plus(1, ChronoUnit.DAYS).toString());
        rate.put("type", "UNKNOWN");
        postJson("/api/coupons", rate).andExpect(status().isBadRequest());

        mvc.perform(get("/api/coupons/NOPE" + uid())).andExpect(status().isNotFound());
    }
}
