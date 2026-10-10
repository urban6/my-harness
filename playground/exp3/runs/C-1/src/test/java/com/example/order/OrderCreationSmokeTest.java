package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class OrderCreationSmokeTest extends AbstractIntegrationTest {

    @Test
    void createOrder_reservesStockAndSnapshotsPrice() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);

        var result = api.postOrder("user-1", "key-1", api.orderBody(null, product, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.subtotal").value(15000))
                .andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.totalPrice").value(15000))
                .andExpect(jsonPath("$.couponCode").value(nullValue()))
                .andExpect(jsonPath("$.paidAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].productId").value(product))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(5000))
                .andReturn();

        JsonNode order = api.json(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/orders/" + order.get("id").asLong());
        Duration ttl = Duration.between(Instant.parse(order.get("createdAt").asText()), Instant.parse(order.get("expiresAt").asText()));
        assertThat(ttl).isEqualTo(Duration.ofMinutes(15));
        assertThat(reserved(product)).isEqualTo(3);
        assertThat(stock(product)).isEqualTo(10);
    }

    @Test
    void createOrder_insufficientStock_returns409AndRollsBackEarlierReservations() throws Exception {
        long plenty = api.createProduct("Plenty", 100, 10);
        long scarce = api.createProduct("Scarce", 100, 1);

        api.postOrder("user-1", "key-1", api.orderBody(null, plenty, 2, scarce, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:insufficient-stock"))
                .andExpect(jsonPath("$.productId").value(scarce))
                .andExpect(jsonPath("$.requested").value(5))
                .andExpect(jsonPath("$.available").value(1));

        assertThat(reserved(plenty)).isZero();
        assertThat(reserved(scarce)).isZero();
        // the failed request did not consume the key
        api.postOrder("user-1", "key-1", api.orderBody(null, plenty, 2)).andExpect(status().isCreated());
    }

    @Test
    void createOrder_unknownProduct_returns404() throws Exception {
        api.postOrder("user-1", "key-1", api.orderBody(null, 424242, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:product-not-found"));
    }

    @Test
    void createOrder_sameKeySameBody_replaysFirstOrderWithoutDoubleReservation() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        JsonNode first = api.createOrder("user-1", "key-1", null, product, 3);

        api.postOrder("user-1", "key-1", api.orderBody(null, product, 3))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/orders/" + first.get("id").asLong()))
                .andExpect(jsonPath("$.id").value(first.get("id").asLong()));

        assertThat(reserved(product)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void createOrder_sameKeyDifferentBody_returns409() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        api.createOrder("user-1", "key-1", null, product, 3);

        api.postOrder("user-1", "key-1", api.orderBody(null, product, 4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
        assertThat(reserved(product)).isEqualTo(3);
    }

    @Test
    void createOrder_missingHeadersOrBadBody_return400() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        api.postOrder(null, "key-1", api.orderBody(null, product, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:missing-header"))
                .andExpect(jsonPath("$.header").value("X-User-Id"));
        api.postOrder("user-1", null, api.orderBody(null, product, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header").value("Idempotency-Key"));
        api.postOrder("user-1", "k", api.orderBody(null, product, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postOrder("user-1", "k", api.orderBody(null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postOrder("user-1", "k", api.orderBody(null, product, 1, product, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
    }

    // ---------------------------------------------------------------- R05 additions

    @Test
    @DisplayName("R05 multi-item order: subtotal is the sum of unitPrice*quantity, items keep request order, every product is reserved")
    void createOrder_multipleItems_sumsSubtotalAndKeepsItemOrder() throws Exception {
        long a = api.createProduct("A", 1000, 10);
        long b = api.createProduct("B", 250, 10);
        long c = api.createProduct("C", 7, 10);

        api.postOrder("u", "k", api.orderBody(null, c, 4, a, 2, b, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].productId").value(c))
                .andExpect(jsonPath("$.items[1].productId").value(a))
                .andExpect(jsonPath("$.items[2].productId").value(b))
                .andExpect(jsonPath("$.subtotal").value(7 * 4 + 1000 * 2 + 250 * 3));

        assertThat(reserved(a)).isEqualTo(2);
        assertThat(reserved(b)).isEqualTo(3);
        assertThat(reserved(c)).isEqualTo(4);
    }

    @Test
    @DisplayName("R05 unitPrice is a snapshot: a later price change does not alter the existing order but applies to new orders")
    void createOrder_unitPriceIsSnapshotAtOrderTime() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        long first = api.createOrder("u", "k1", null, product, 2).get("id").asLong();

        jdbc.update("UPDATE products SET price = 9000 WHERE id = ?", product);

        api.getOrder(first).andExpect(jsonPath("$.items[0].unitPrice").value(5000)).andExpect(jsonPath("$.subtotal").value(10000));
        api.postOrder("u", "k2", api.orderBody(null, product, 2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(9000))
                .andExpect(jsonPath("$.subtotal").value(18000));
    }

    @Test
    @DisplayName("R05 ordering exactly the available quantity succeeds, one more unit is 409 with available=0 and changes nothing")
    void createOrder_exactAvailableBoundary() throws Exception {
        long product = api.createProduct("Last", 100, 5);
        api.createOrder("u1", "k1", null, product, 2);

        api.postOrder("u2", "k2", api.orderBody(null, product, 3)).andExpect(status().isCreated());
        api.getProductAvailable(product, 0);
        api.postOrder("u3", "k3", api.orderBody(null, product, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:insufficient-stock"))
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.available").value(0));
        assertThat(reserved(product)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("R05 an unknown product among valid ones is 404 and leaves no reservation, order or coupon use behind")
    void createOrder_unknownProductAmongValidOnes_rollsBackEverything() throws Exception {
        long good = api.createProduct("Good", 100, 10);
        api.createCoupon("C1", "FIXED", 10, null, null, 3);

        api.postOrder("u", "k", api.orderBody("C1", good, 2, 424242, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:product-not-found"))
                .andExpect(jsonPath("$.productId").value(424242));

        assertThat(reserved(good)).isZero();
        assertThat(couponUsed("C1")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    @DisplayName("R05 body validation: quantity 0/-1/10001, 101 items, null item, blank or empty couponCode and non-numeric quantity are 400")
    void createOrder_bodyValidation() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10_000);
        String validation = "urn:problem:order-payment:validation-failed";
        api.postOrder("u", "k", api.orderBody(null, product, -1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));
        api.postOrder("u", "k", api.orderBody(null, product, 10_001)).andExpect(status().isBadRequest());
        api.postOrder("u", "k", api.orderBody(null, product, 10_000)).andExpect(status().isCreated());
        api.postOrder("u", "k2", api.orderBody("", product, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));
        api.postOrder("u", "k3", api.orderBody("   ", product, 1)).andExpect(status().isBadRequest());

        var tooMany = api.orderBody(null);
        for (int i = 0; i < 101; i++) {
            tooMany.withArray("items").addObject().put("productId", 1000 + i).put("quantity", 1);
        }
        api.postOrder("u", "k4", tooMany).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));

        var missingItems = objectMapper.createObjectNode();
        api.postOrder("u", "k5", missingItems).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));
        var stringQty = objectMapper.createObjectNode();
        stringQty.putArray("items").addObject().put("productId", product).put("quantity", "two");
        api.postOrder("u", "k6", stringQty).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:malformed-request"));
        var missingQty = objectMapper.createObjectNode();
        missingQty.putArray("items").addObject().put("productId", product);
        api.postOrder("u", "k7", missingQty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(validation));
        assertThat(reserved(product)).isEqualTo(10_000); // only the one valid order reserved stock
    }

    @Test
    @DisplayName("R05 X-User-Id must be 1..64 non-blank characters (blank and 65 chars are 400, 64 is accepted)")
    void createOrder_userIdBounds() throws Exception {
        long product = api.createProduct("Mouse", 5000, 100);
        api.postOrder("   ", "k1", api.orderBody(null, product, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postOrder("u".repeat(65), "k2", api.orderBody(null, product, 1)).andExpect(status().isBadRequest());
        api.postOrder("u".repeat(64), "k3", api.orderBody(null, product, 1)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("R05 the reservation changes available but not stock, and expiresAt is exactly createdAt + TTL (PT15M default)")
    void createOrder_expiresAtIsCreatedAtPlusTtl() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        JsonNode order = api.createOrder("u", "k", null, product, 4);

        assertThat(Instant.parse(order.get("createdAt").asText())).isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(Instant.parse(order.get("expiresAt").asText()))
                .isEqualTo(Instant.parse(order.get("createdAt").asText()).plus(Duration.ofMinutes(15)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/products/" + product))
                .andExpect(jsonPath("$.stock").value(10)).andExpect(jsonPath("$.reserved").value(4)).andExpect(jsonPath("$.available").value(6));
    }

    // ---------------------------------------------------------------- R07 additions

    @Test
    @DisplayName("R07 key rules: 128 printable ASCII chars accepted; 129 chars, whitespace and blank keys are 400 validation-failed")
    void idempotencyKey_formatRules() throws Exception {
        long product = api.createProduct("Mouse", 5000, 100);
        api.postOrder("u", "k".repeat(128), api.orderBody(null, product, 1)).andExpect(status().isCreated());
        api.postOrder("u", "k".repeat(129), api.orderBody(null, product, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.postOrder("u", "has space", api.orderBody(null, product, 1)).andExpect(status().isBadRequest());
        api.postOrder("u", "   ", api.orderBody(null, product, 1)).andExpect(status().isBadRequest());
        assertThat(reserved(product)).isEqualTo(1);
    }

    @Test
    @DisplayName("R07 the same Idempotency-Key from two different users creates two independent orders")
    void idempotencyKey_isScopedPerUser() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        long first = api.createOrder("alice", "shared", null, product, 1).get("id").asLong();
        var second = api.postOrder("bob", "shared", api.orderBody(null, product, 1))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        assertThat(api.json(second).get("id").asLong()).isNotEqualTo(first);
        assertThat(reserved(product)).isEqualTo(2);
    }

    @Test
    @DisplayName("R07 items listed in a different order are the same request and replay; a different coupon or quantity is a 409")
    void idempotencyKey_requestHashNormalisation() throws Exception {
        long a = api.createProduct("A", 100, 10);
        long b = api.createProduct("B", 100, 10);
        api.createCoupon("C1", "FIXED", 10, null, null, 5);
        api.createCoupon("C2", "FIXED", 10, null, null, 5);
        long id = api.createOrder("u", "k", "C1", a, 1, b, 2).get("id").asLong();

        api.postOrder("u", "k", api.orderBody("C1", b, 2, a, 1))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        api.postOrder("u", "k", api.orderBody("C2", a, 1, b, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:idempotency-key-conflict"));
        api.postOrder("u", "k", api.orderBody(null, a, 1, b, 2)).andExpect(status().isConflict());
        api.postOrder("u", "k", api.orderBody("C1", a, 1)).andExpect(status().isConflict());

        assertThat(couponUsed("C1")).isEqualTo(1);
        assertThat(couponUsed("C2")).isZero();
        assertThat(reserved(a)).isEqualTo(1);
        assertThat(reserved(b)).isEqualTo(2);
    }

    @Test
    @DisplayName("R07 requests that fail (404, 422, 409, 400) do not consume the key: the same key succeeds afterwards")
    void idempotencyKey_failedRequestsDoNotConsumeTheKey() throws Exception {
        long product = api.createProduct("Mouse", 5000, 2);
        api.createCoupon("BIG", "FIXED", 10, 1_000_000L, null, 5);

        api.postOrder("u", "k", api.orderBody(null, 999_999, 1)).andExpect(status().isNotFound());
        api.postOrder("u", "k", api.orderBody("NOPE", product, 1)).andExpect(status().isNotFound());
        api.postOrder("u", "k", api.orderBody("BIG", product, 1)).andExpect(status().isUnprocessableEntity());
        api.postOrder("u", "k", api.orderBody(null, product, 3)).andExpect(status().isConflict());
        api.postOrder("u", "k", api.orderBody(null, product, 0)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'k'", Integer.class)).isZero();

        api.postOrder("u", "k", api.orderBody(null, product, 2))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(reserved(product)).isEqualTo(2);
    }

    @Test
    @DisplayName("R07 a replay after the order changed state returns the order as it is now and charges nothing again")
    void idempotencyKey_replayReflectsCurrentState() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        api.createCoupon("C1", "FIXED", 100, null, null, 5);
        long id = api.createOrder("u", "k", "C1", product, 2).get("id").asLong();
        api.pay(id, "pay-1", "tok").andExpect(status().isOk());

        api.postOrder("u", "k", api.orderBody("C1", product, 2))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(couponUsed("C1")).isEqualTo(1);
        assertThat(GATEWAY.charges()).hasSize(1);
    }

    // ---------------------------------------------------------------- R08

    @Test
    @DisplayName("R08 GET /api/orders/{id} returns exactly the documented fields (no payment, lease or idempotency internals)")
    void getOrder_exposesExactlyTheDocumentedFields() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        long id = api.createOrder("u", "k", null, product, 1).get("id").asLong();

        JsonNode body = api.json(api.getOrder(id).andExpect(status().isOk()).andReturn());
        var names = new java.util.TreeSet<String>();
        body.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "userId", "status", "items", "couponCode", "subtotal", "discount",
                "totalPrice", "createdAt", "expiresAt", "paidAt");
        var itemNames = new java.util.TreeSet<String>();
        body.get("items").get(0).fieldNames().forEachRemaining(itemNames::add);
        assertThat(itemNames).containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
        assertThat(body.get("couponCode").isNull()).isTrue();
        assertThat(body.get("paidAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("R08 after payment GET shows PAID and a paidAt that equals the pay response and the stored value")
    void getOrder_afterPayment_showsPaidAt() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        long id = api.createOrder("u", "k", null, product, 1).get("id").asLong();
        clock.advance(Duration.ofMinutes(3));
        JsonNode paid = api.json(api.pay(id, "pay-1", "tok").andExpect(status().isOk()).andReturn());

        JsonNode fetched = api.json(api.getOrder(id).andReturn());
        assertThat(fetched.get("status").asText()).isEqualTo("PAID");
        assertThat(fetched.get("paidAt").asText()).isEqualTo(paid.get("paidAt").asText());
        assertThat(Instant.parse(fetched.get("paidAt").asText())).isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(Instant.parse(fetched.get("paidAt").asText())).isAfter(Instant.parse(fetched.get("createdAt").asText()));
    }

    @Test
    @DisplayName("R08 unknown order id is 404 order-not-found with the orderId extension")
    void getOrder_unknownId_returns404WithOrderId() throws Exception {
        api.getOrder(31337)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:order-not-found"))
                .andExpect(jsonPath("$.orderId").value(31337));
    }

    @Test
    @DisplayName("R05/R16 null items, a null productId and a missing items array are 400 validation errors, never 500")
    void createOrder_nullElements_return400() throws Exception {
        long product = api.createProduct("Mouse", 5000, 10);
        String validation = "urn:problem:order-payment:validation-failed";
        for (String json : new String[] {"{\"items\":[null]}", "{\"items\":[{\"quantity\":1}]}", "{\"items\":null}",
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":null}]}"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                            .header("X-User-Id", "u").header("Idempotency-Key", "k")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(validation));
        }
        assertThat(reserved(product)).isZero();
    }
}
