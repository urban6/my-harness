package com.example.order;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CatalogApiTest extends IntegrationTestBase {

    @Test
    void createsProductAndExposesAvailability() throws Exception {
        postJson("/api/products", Map.of("name", "keyboard", "price", 1500, "stock", 10))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/products/")))
                .andExpect(jsonPath("$.name").value("keyboard"))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10));
    }

    @Test
    void invalidProductIsRejectedWithProblemDetails() throws Exception {
        postJson("/api/products", Map.of("name", " ", "price", -1, "stock", 1))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void unknownProductIs404ProblemDetails() throws Exception {
        mvc.perform(get("/api/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createsAndReadsCoupon() throws Exception {
        String code = "WELCOME-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("code", code, "type", "RATE", "value", 10, "minOrderAmount", 1000,
                "maxDiscountAmount", 500, "totalQuantity", 5,
                "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2999-01-01T00:00:00Z");
        postJson("/api/coupons", body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/coupons/" + code));
        mvc.perform(get("/api/coupons/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("RATE"))
                .andExpect(jsonPath("$.value").value(10))
                .andExpect(jsonPath("$.minOrderAmount").value(1000))
                .andExpect(jsonPath("$.maxDiscountAmount").value(500))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.validFrom").value("2026-01-01T00:00:00Z"));
        postJson("/api/coupons", body).andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidCoupons() throws Exception {
        Map<String, Object> rate = Map.of("code", "X-" + UUID.randomUUID(), "type", "RATE", "value", 150,
                "minOrderAmount", 0, "totalQuantity", 1,
                "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2999-01-01T00:00:00Z");
        postJson("/api/coupons", rate).andExpect(status().isBadRequest());

        Map<String, Object> reversed = Map.of("code", "Y-" + UUID.randomUUID(), "type", "FIXED", "value", 100,
                "minOrderAmount", 0, "totalQuantity", 1,
                "validFrom", "2999-01-01T00:00:00Z", "validUntil", "2026-01-01T00:00:00Z");
        postJson("/api/coupons", reversed).andExpect(status().isBadRequest());

        mvc.perform(get("/api/coupons/does-not-exist")).andExpect(status().isNotFound());
    }
}
