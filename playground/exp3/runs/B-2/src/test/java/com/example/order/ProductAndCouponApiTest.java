package com.example.order;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductAndCouponApiTest extends IntegrationTest {

    @Test
    void createProduct_returns201WithLocationAndAvailable() throws Exception {
        postJson("/api/products", """
                {"name":"키보드","price":50000,"stock":10}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/products/")))
                .andExpect(jsonPath("$.name").value("키보드"))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10));
    }

    @Test
    void createProduct_returns400ProblemJson_whenInvalid() throws Exception {
        postJson("/api/products", """
                {"name":"","price":-1,"stock":1.5}""")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        postJson("/api/products", """
                {"name":"x","price":0,"stock":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void getProduct_returns404ProblemJson_whenMissing() throws Exception {
        mvc.perform(get("/api/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void createAndGetCoupon_roundTripWithUsedCount() throws Exception {
        String body = """
                {"code":"WELCOME-1","type":"RATE","value":10,"minOrderAmount":1000,"maxDiscountAmount":5000,
                 "totalQuantity":100,"validFrom":"2020-01-01T00:00:00Z","validUntil":"2099-01-01T00:00:00Z"}""";
        postJson("/api/coupons", body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/coupons/WELCOME-1")));
        mvc.perform(get("/api/coupons/WELCOME-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RATE"))
                .andExpect(jsonPath("$.value").value(10))
                .andExpect(jsonPath("$.maxDiscountAmount").value(5000))
                .andExpect(jsonPath("$.usedCount").value(0));
        postJson("/api/coupons", body).andExpect(status().isConflict());
    }

    @Test
    void createCoupon_returns400_whenRateOver100OrPeriodReversed() throws Exception {
        postJson("/api/coupons", """
                {"code":"BAD-RATE","type":"RATE","value":150,"totalQuantity":1,
                 "validFrom":"2020-01-01T00:00:00Z","validUntil":"2099-01-01T00:00:00Z"}""")
                .andExpect(status().isBadRequest());
        postJson("/api/coupons", """
                {"code":"BAD-PERIOD","type":"FIXED","value":100,"totalQuantity":1,
                 "validFrom":"2099-01-01T00:00:00Z","validUntil":"2020-01-01T00:00:00Z"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCoupon_returns404_whenMissing() throws Exception {
        mvc.perform(get("/api/coupons/NOPE")).andExpect(status().isNotFound());
    }
}
