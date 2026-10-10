package com.example.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.example.order.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.DisplayName;
import java.time.Instant;
import org.springframework.test.web.servlet.ResultActions;
import com.example.order.support.PaymentGatewayStub.Behavior;

class ProblemFormatSmokeTest extends AbstractIntegrationTest {

    private static final String PROBLEM = "application/problem+json";

    @Test
    void notFoundProblem_hasAllRfc9457Members() throws Exception {
        mvc.perform(get("/api/orders/777"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-not-found"))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value("/api/orders/777"));
    }

    @Test
    void unknownPath_returns404ProblemJson() throws Exception {
        mvc.perform(get("/api/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:resource-not-found"));
    }

    @Test
    void wrongMethod_returns405ProblemJson() throws Exception {
        mvc.perform(delete("/api/products/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:method-not-allowed"));
    }

    @Test
    void wrongContentType_returns415ProblemJson() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:unsupported-media-type"));
    }

    @Test
    void malformedBodyOrTypes_return400MalformedRequest() throws Exception {
        String malformed = "urn:problem:order-payment:malformed-request";
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(malformed));
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"price\":\"abc\",\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(malformed));
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"price\":10.5,\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(malformed));
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(malformed));
        mvc.perform(get("/api/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(malformed));
    }

    @Test
    void missingNumericField_isValidationFailedNotZero() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    // ---------------------------------------------------------------- R16: one representative per slug of the 01 section 1.1 table

    private static final String URN = "urn:problem:order-payment:";

    private ResultActions expectProblem(ResultActions result, int httpStatus, String slug, String instance) throws Exception {
        return result
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(URN + slug))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value(instance));
    }

    @Test
    @DisplayName("R16 400 family: validation-failed, missing-header, malformed-request, invalid-cursor are problem+json with type/title/status/detail/instance")
    void badRequestFamily_isProblemJson() throws Exception {
        long product = api.createProduct("Desk", 1000, 5);
        expectProblem(api.postProduct(api.objectMapper().createObjectNode().put("name", "").put("price", 1).put("stock", 1)),
                400, "validation-failed", "/api/products").andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isString());
        expectProblem(api.postOrder("u", null, api.orderBody(null, product, 1)), 400, "missing-header", "/api/orders")
                .andExpect(jsonPath("$.header").value("Idempotency-Key"));
        expectProblem(api.pay(1, null, "tok"), 400, "missing-header", "/api/orders/1/pay");
        expectProblem(mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content("[1,2")),
                400, "malformed-request", "/api/coupons");
        expectProblem(api.listOrders("cursor=@@"), 400, "invalid-cursor", "/api/orders");
    }

    @Test
    @DisplayName("R16 404/405/415/406 family: *-not-found, resource-not-found, method-not-allowed, unsupported-media-type, not-acceptable")
    void routingFamily_isProblemJson() throws Exception {
        expectProblem(mvc.perform(get("/api/products/9")), 404, "product-not-found", "/api/products/9").andExpect(jsonPath("$.productId").value(9));
        expectProblem(mvc.perform(get("/api/coupons/NOPE")), 404, "coupon-not-found", "/api/coupons/NOPE").andExpect(jsonPath("$.couponCode").value("NOPE"));
        expectProblem(mvc.perform(get("/api/orders/9")), 404, "order-not-found", "/api/orders/9").andExpect(jsonPath("$.orderId").value(9));
        expectProblem(mvc.perform(post("/api/orders/9/cancel")), 404, "order-not-found", "/api/orders/9/cancel");
        expectProblem(mvc.perform(get("/no/such/route")), 404, "resource-not-found", "/no/such/route");
        expectProblem(mvc.perform(delete("/api/orders/1")), 405, "method-not-allowed", "/api/orders/1");
        expectProblem(mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_XML).content("<a/>")),
                415, "unsupported-media-type", "/api/coupons");
        long product = api.createProduct("Desk", 1000, 5);
        expectProblem(mvc.perform(get("/api/products/" + product).accept(MediaType.APPLICATION_XML)),
                406, "not-acceptable", "/api/products/" + product);
    }

    @Test
    @DisplayName("R16 409 family: coupon-code-duplicate, insufficient-stock, invalid-order-state, order-expired, idempotency-key-conflict, operation-in-progress")
    void conflictFamily_isProblemJson() throws Exception {
        long product = api.createProduct("Desk", 1000, 2);
        api.createCoupon("DUP", "FIXED", 100, null, null, 5);
        Instant now = Instant.now();
        expectProblem(api.postCoupon(api.couponBody("DUP", "FIXED", 100, null, null, 5, now, now.plusSeconds(60))),
                409, "coupon-code-duplicate", "/api/coupons").andExpect(jsonPath("$.couponCode").value("DUP"));
        expectProblem(api.postOrder("u", "big", api.orderBody(null, product, 3)), 409, "insufficient-stock", "/api/orders")
                .andExpect(jsonPath("$.productId").value(product)).andExpect(jsonPath("$.requested").value(3)).andExpect(jsonPath("$.available").value(2));

        long order = api.createOrder("u", "k1", null, product, 1).get("id").asLong();
        expectProblem(api.postOrder("u", "k1", api.orderBody(null, product, 2)), 409, "idempotency-key-conflict", "/api/orders");
        expectProblem(api.action(order, "ship"), 409, "invalid-order-state", "/api/orders/" + order + "/ship")
                .andExpect(jsonPath("$.currentStatus").value("PENDING_PAYMENT")).andExpect(jsonPath("$.action").value("ship"));

        jdbc.update("UPDATE orders SET lease_kind='PAY', lease_token=gen_random_uuid(), lease_expires_at=? WHERE id=?",
                java.sql.Timestamp.from(clock.instant().plusSeconds(30)), order);
        expectProblem(api.action(order, "cancel"), 409, "operation-in-progress", "/api/orders/" + order + "/cancel")
                .andExpect(jsonPath("$.operation").value("PAY")).andExpect(header().string("Retry-After", "1"));
        jdbc.update("UPDATE orders SET lease_kind=NULL, lease_token=NULL, lease_expires_at=NULL WHERE id=?", order);

        clock.advance(java.time.Duration.ofMinutes(16));
        expectProblem(api.pay(order, "pay-1", "tok"), 409, "order-expired", "/api/orders/" + order + "/pay")
                .andExpect(jsonPath("$.expiresAt").isString());
    }

    @Test
    @DisplayName("R16 422 family: coupon-not-in-period, coupon-min-order-not-met, coupon-exhausted")
    void businessRuleFamily_isProblemJson() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        Instant now = Instant.now();
        api.postCoupon(api.couponBody("OLD", "FIXED", 100, null, null, 5, now.minus(java.time.Duration.ofDays(2)), now.minus(java.time.Duration.ofDays(1))));
        api.createCoupon("MIN", "FIXED", 100, 99_999L, null, 5);
        api.createCoupon("ONE", "FIXED", 100, null, null, 1);
        api.createOrder("u0", "k0", "ONE", product, 1);

        expectProblem(api.postOrder("u", "k1", api.orderBody("OLD", product, 1)), 422, "coupon-not-in-period", "/api/orders")
                .andExpect(jsonPath("$.couponCode").value("OLD"));
        expectProblem(api.postOrder("u", "k2", api.orderBody("MIN", product, 1)), 422, "coupon-min-order-not-met", "/api/orders")
                .andExpect(jsonPath("$.minOrderAmount").value(99_999)).andExpect(jsonPath("$.subtotal").value(1000));
        expectProblem(api.postOrder("u", "k3", api.orderBody("ONE", product, 1)), 422, "coupon-exhausted", "/api/orders");
    }

    @Test
    @DisplayName("R16 502/504: pg-gateway-error and pg-gateway-timeout carry orderId and retryable, for charge and for refund")
    void gatewayFamily_isProblemJson() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        long order = api.createOrder("u", "k1", null, product, 1).get("id").asLong();

        GATEWAY.chargeBehavior(Behavior.SERVER_ERROR);
        expectProblem(api.pay(order, "pay-1", "tok"), 502, "pg-gateway-error", "/api/orders/" + order + "/pay")
                .andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.retryable").value(true));
        GATEWAY.chargeBehavior(Behavior.OK);
        GATEWAY.delayMillis(2500);
        expectProblem(api.pay(order, "pay-1", "tok"), 504, "pg-gateway-timeout", "/api/orders/" + order + "/pay")
                .andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.retryable").value(true));

        GATEWAY.delayMillis(0);
        long paid = api.createPaidOrder("u", "k2", null, product, 1);
        GATEWAY.refundBehavior(Behavior.SERVER_ERROR);
        expectProblem(api.action(paid, "cancel"), 502, "pg-gateway-error", "/api/orders/" + paid + "/cancel");
        GATEWAY.refundBehavior(Behavior.OK);
        GATEWAY.delayMillis(2500);
        expectProblem(api.action(paid, "cancel"), 504, "pg-gateway-timeout", "/api/orders/" + paid + "/cancel");
    }

    @Test
    @DisplayName("R16 an unexpected server fault is 500 internal-error with a fixed detail and no stack trace, class or SQL leaking")
    void unexpectedFault_is500WithoutInternalDetails() throws Exception {
        long product = api.createProduct("Desk", 1000, 10);
        long paid = api.createPaidOrder("u", "k1", null, product, 1);
        jdbc.update("DELETE FROM payments WHERE order_id = ?", paid); // corrupt state: PAID without a payment row

        var result = expectProblem(api.action(paid, "cancel"), 500, "internal-error", "/api/orders/" + paid + "/cancel")
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception").doesNotContain("com.example").doesNotContain("payment row")
                .doesNotContain("SELECT").doesNotContain("trace").doesNotContain("\tat ");
        api.getOrder(paid).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT lease_token IS NULL FROM orders WHERE id = ?", Boolean.class, paid))
                .as("the failed refund attempt left no lease behind").isTrue();
        assertThat(GATEWAY.refunds()).isEmpty();
    }

    @Test
    @DisplayName("R16 null extension members are omitted from problems, and no problem exposes framework fields (exception, trace, path)")
    void problems_haveNoNullOrFrameworkMembers() throws Exception {
        var result = mvc.perform(get("/api/orders/777")).andReturn();
        var body = api.json(result);
        var names = new java.util.TreeSet<String>();
        body.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "orderId");
        body.fields().forEachRemaining(f -> assertThat(f.getValue().isNull()).as(f.getKey()).isFalse());
    }
}
