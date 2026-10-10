package com.example.order;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.DisplayName;

class ProductCouponSmokeTest extends AbstractIntegrationTest {

    @Test
    void createProduct_returns201WithLocation_andGetShowsAvailable() throws Exception {
        var created = api.postProduct(api.objectMapper().createObjectNode()
                        .put("name", "  Keyboard ").put("price", 12000).put("stock", 10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10))
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        org.assertj.core.api.Assertions.assertThat(location).matches("/api/products/\\d+");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(12000))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.available").value(10));
    }

    @Test
    void createProduct_withInvalidFields_returns400ValidationFailed() throws Exception {
        api.postProduct(api.objectMapper().createObjectNode().put("name", "   ").put("price", -1).put("stock", -5))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(3)));
    }

    @Test
    void getProduct_unknownId_returns404() throws Exception {
        mvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:product-not-found"))
                .andExpect(jsonPath("$.productId").value(999));
    }

    @Test
    void createAndGetCoupon_roundTripsFieldsWithUsedCountZero() throws Exception {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant until = from.plus(Duration.ofDays(30));
        api.postCoupon(api.couponBody("WELCOME10", "RATE", 10, null, null, 100, from, until))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/coupons/WELCOME10"))
                .andExpect(jsonPath("$.minOrderAmount").value(0))
                .andExpect(jsonPath("$.maxDiscountAmount").value(nullValue()))
                .andExpect(jsonPath("$.usedCount").value(0));

        mvc.perform(get("/api/coupons/WELCOME10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RATE"))
                .andExpect(jsonPath("$.value").value(10))
                .andExpect(jsonPath("$.totalQuantity").value(100))
                .andExpect(jsonPath("$.validFrom").value("2030-01-01T00:00:00Z"));
    }

    @Test
    void createCoupon_duplicateCode_returns409() throws Exception {
        api.createCoupon("DUP", "FIXED", 1000, null, null, 5);
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("DUP", "FIXED", 1000, null, null, 5, now, now.plusSeconds(60)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-code-duplicate"))
                .andExpect(jsonPath("$.couponCode").value("DUP"));
    }

    @Test
    void createCoupon_rateAbove100_periodReversedOrUnknownType_return400() throws Exception {
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("R101", "RATE", 101, null, null, 5, now, now.plusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postCoupon(api.couponBody("REV", "FIXED", 10, null, null, 5, now.plusSeconds(60), now))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postCoupon(api.couponBody("FOO", "PERCENT", 10, null, null, 5, now, now.plusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:malformed-request"));
    }

    @Test
    void getCoupon_unknownCode_returns404() throws Exception {
        mvc.perform(get("/api/coupons/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:coupon-not-found"));
    }

    // ---------------------------------------------------------------- R01 / R02 edge cases

    @Test
    @DisplayName("R01 boundary values are accepted: price 0, stock 0, price/stock 1_000_000_000, 255-char name")
    void createProduct_boundaryValues_areAccepted() throws Exception {
        api.postProduct(api.objectMapper().createObjectNode().put("name", "free").put("price", 0).put("stock", 0))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.available").value(0));
        api.postProduct(api.objectMapper().createObjectNode().put("name", "max").put("price", 1_000_000_000L).put("stock", 1_000_000_000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.price").value(1_000_000_000L));
        api.postProduct(api.objectMapper().createObjectNode().put("name", "x".repeat(255)).put("price", 1).put("stock", 1))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("R01 out-of-range values are rejected: price/stock above 1e9, 256-char name, missing name, negative price only")
    void createProduct_outOfRangeValues_return400() throws Exception {
        String validation = "urn:problem:order-payment:validation-failed";
        api.postProduct(api.objectMapper().createObjectNode().put("name", "a").put("price", 1_000_000_001L).put("stock", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation))
                .andExpect(jsonPath("$.errors[0].field").value("price"));
        api.postProduct(api.objectMapper().createObjectNode().put("name", "a").put("price", 1).put("stock", 1_000_000_001L))
                .andExpect(status().isBadRequest());
        api.postProduct(api.objectMapper().createObjectNode().put("name", "x".repeat(256)).put("price", 1).put("stock", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        api.postProduct(api.objectMapper().createObjectNode().put("price", 1).put("stock", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));
        api.postProduct(api.objectMapper().createObjectNode().put("name", "a").put("price", -1).put("stock", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)));
        api.postProduct(api.objectMapper().createObjectNode().put("name", "a").put("price", 1).put("stock", -1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
    }

    @Test
    @DisplayName("R02 available is stock - reserved and follows reservations, cancellations")
    void getProduct_availableTracksReservations() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        long first = api.createOrder("u1", "k1", null, product, 3).get("id").asLong();
        api.createOrder("u2", "k2", null, product, 2);

        mvc.perform(get("/api/products/" + product))
                .andExpect(jsonPath("$.id").value(product))
                .andExpect(jsonPath("$.name").value("Desk"))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.reserved").value(5))
                .andExpect(jsonPath("$.available").value(5));

        api.action(first, "cancel").andReturn();
        mvc.perform(get("/api/products/" + product))
                .andExpect(jsonPath("$.reserved").value(2))
                .andExpect(jsonPath("$.available").value(8));
    }

    @Test
    @DisplayName("R02 a non-numeric product id is 400 malformed-request, not 404")
    void getProduct_nonNumericId_returns400() throws Exception {
        mvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:malformed-request"));
    }

    // ---------------------------------------------------------------- R03 / R04 edge cases

    @Test
    @DisplayName("R03 optional fields: maxDiscountAmount 0 and a value are stored as given, offset timestamps are normalised to UTC")
    void createCoupon_optionalFieldsAndOffsets_roundTrip() throws Exception {
        var om = api.objectMapper();
        var body = api.couponBody("CAP0", "RATE", 10, 500L, 0L, 3, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-02-01T00:00:00Z"));
        api.postCoupon(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxDiscountAmount").value(0))
                .andExpect(jsonPath("$.minOrderAmount").value(500));
        var offset = om.createObjectNode().put("code", "TZ").put("type", "FIXED").put("value", 100).put("totalQuantity", 1)
                .put("validFrom", "2030-01-01T09:00:00+09:00").put("validUntil", "2030-01-02T09:00:00+09:00");
        api.postCoupon(offset).andExpect(status().isCreated())
                .andExpect(jsonPath("$.validFrom").value("2030-01-01T00:00:00Z"));
        api.postCoupon(api.couponBody("CAP9", "FIXED", 100, null, 9000L, 3, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-02-01T00:00:00Z")))
                .andExpect(jsonPath("$.maxDiscountAmount").value(9000));
        mvc.perform(get("/api/coupons/CAP9")).andExpect(jsonPath("$.maxDiscountAmount").value(9000)).andExpect(jsonPath("$.usedCount").value(0));
    }

    @Test
    @DisplayName("R03 RATE value 100 and 1 are accepted, 0 and 101 rejected; FIXED value 0 rejected")
    void createCoupon_valueBoundaries() throws Exception {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(3600);
        api.postCoupon(api.couponBody("R100", "RATE", 100, null, null, 1, now, until)).andExpect(status().isCreated());
        api.postCoupon(api.couponBody("R1", "RATE", 1, null, null, 1, now, until)).andExpect(status().isCreated());
        api.postCoupon(api.couponBody("R0", "RATE", 0, null, null, 1, now, until)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postCoupon(api.couponBody("R101", "RATE", 101, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("F0", "FIXED", 0, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("F-1", "FIXED", -5, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("F100", "FIXED", 100000, null, null, 1, now, until)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("R03 negative minOrderAmount/maxDiscountAmount, totalQuantity 0 and equal validFrom/validUntil are rejected")
    void createCoupon_negativeAndDegenerateFields_return400() throws Exception {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(3600);
        api.postCoupon(api.couponBody("NEGMIN", "FIXED", 100, -1L, null, 1, now, until)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postCoupon(api.couponBody("NEGMAX", "FIXED", 100, null, -1L, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("QTY0", "FIXED", 100, null, null, 0, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("SAME", "FIXED", 100, null, null, 1, now, now)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM coupons", Integer.class)).isZero();
    }

    @Test
    @DisplayName("R03 code must be 1..64 chars of [A-Za-z0-9_-]; codes are case-sensitive; a missing type is a validation error")
    void createCoupon_codeRulesAndCaseSensitivity() throws Exception {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(3600);
        api.postCoupon(api.couponBody("has space", "FIXED", 100, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("slash/inside", "FIXED", 100, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("", "FIXED", 100, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("x".repeat(65), "FIXED", 100, null, null, 1, now, until)).andExpect(status().isBadRequest());
        api.postCoupon(api.couponBody("x".repeat(64), "FIXED", 100, null, null, 1, now, until)).andExpect(status().isCreated());
        api.postCoupon(api.couponBody("Save", "FIXED", 100, null, null, 1, now, until)).andExpect(status().isCreated());
        api.postCoupon(api.couponBody("SAVE", "FIXED", 100, null, null, 1, now, until)).andExpect(status().isCreated());
        var noType = api.couponBody("NOTYPE", "FIXED", 100, null, null, 1, now, until);
        noType.remove("type");
        api.postCoupon(noType).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        mvc.perform(get("/api/coupons/save")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R04 usedCount in GET /api/coupons/{code} follows order creation and cancellation")
    void getCoupon_usedCountFollowsOrders() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        api.createCoupon("USED", "FIXED", 100, null, null, 5);
        long orderId = api.createOrder("u", "k1", "USED", product, 1).get("id").asLong();
        api.createOrder("u", "k2", "USED", product, 1);

        mvc.perform(get("/api/coupons/USED")).andExpect(jsonPath("$.usedCount").value(2)).andExpect(jsonPath("$.totalQuantity").value(5));
        api.action(orderId, "cancel").andReturn();
        mvc.perform(get("/api/coupons/USED")).andExpect(jsonPath("$.usedCount").value(1));
    }

    @Test
    @DisplayName("R04 the coupon response carries exactly the documented fields and no internal id")
    void getCoupon_exposesOnlyDocumentedFields() throws Exception {
        api.createCoupon("SHAPE", "FIXED", 100, null, null, 5);
        var body = api.json(mvc.perform(get("/api/coupons/SHAPE")).andReturn());
        var names = new java.util.TreeSet<String>();
        body.fieldNames().forEachRemaining(names::add);
        org.assertj.core.api.Assertions.assertThat(names).containsExactlyInAnyOrder("code", "type", "value", "minOrderAmount",
                "maxDiscountAmount", "totalQuantity", "usedCount", "validFrom", "validUntil");
    }
}
