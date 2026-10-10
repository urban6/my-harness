package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** change-request.md M1-M8 (multi-tenancy via X-Tenant-Id). */
class MultiTenancyTest extends ApiTestSupport {

    Res couponIn(String tenant, String code, long totalQuantity) throws Exception {
        String from = Instant.now().minusSeconds(60).toString();
        String until = Instant.now().plusSeconds(3600).toString();
        return callAs(tenant, "POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"FIXED\",\"value\":100,"
                + "\"totalQuantity\":" + totalQuantity + ",\"validFrom\":\"" + from + "\",\"validUntil\":\""
                + until + "\"}");
    }

    static String items(long productId, long quantity) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    static String items(long productId, long quantity, String couponCode) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}],\"couponCode\":\""
                + couponCode + "\"}";
    }

    static String code() {
        return "MT" + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    }

    static void assertProblem(Res r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo(code);
        assertThat(r.body().has("type")).isTrue();
        assertThat(r.body().has("title")).isTrue();
        assertThat(r.body().has("detail")).isTrue();
        assertThat(r.body().get("status").asInt()).isEqualTo(status);
    }

    // ------------------------------------------------------------------ M1

    @Test
    void m1TenantHeaderIsRequiredAndValidatedOnEveryApiEndpoint() throws Exception {
        long p = product(100, 10);
        long o = order("u", uniq(), items(p, 1)).body().get("id").asLong();
        String[][] endpoints = {
                {"POST", "/api/products", "{\"name\":\"p\",\"price\":1,\"stock\":1}"},
                {"GET", "/api/products/" + p, null},
                {"POST", "/api/coupons", "{}"},
                {"GET", "/api/coupons/ABCD", null},
                {"POST", "/api/orders", items(p, 1)},
                {"GET", "/api/orders/" + o, null},
                {"GET", "/api/orders", null},
                {"POST", "/api/orders/" + o + "/pay", "{\"cardToken\":\"ok\"}"},
                {"POST", "/api/orders/" + o + "/cancel", null},
                {"POST", "/api/orders/" + o + "/ship", null},
                {"POST", "/api/orders/" + o + "/deliver", null},
        };
        String[] invalid = {null, "", "ACME", "acme_1", "acme.io", "a".repeat(31), "acme!", "acme/1"};
        for (String[] e : endpoints) {
            for (String tenant : invalid) {
                Res r = callAs(tenant, e[0], e[1], e[2], "X-User-Id", "u", "Idempotency-Key", uniq());
                assertProblem(r, 400, "VALIDATION_ERROR");
            }
        }
        // nothing was changed by the rejected requests
        JsonNode prod = call("GET", "/api/products/" + p, null).body();
        assertThat(prod.get("reserved").asLong()).isEqualTo(1);
        assertThat(call("GET", "/api/orders/" + o, null).body().get("status").asText()).isEqualTo("PENDING_PAYMENT");

        // boundaries of the format: 1 and 30 characters, lowercase letters, digits and '-'
        assertThat(callAs("a", "GET", "/api/orders", null).status()).isEqualTo(200);
        assertThat(callAs("a".repeat(30), "GET", "/api/orders", null).status()).isEqualTo(200);
        assertThat(callAs("acme-01", "GET", "/api/orders", null).status()).isEqualTo(200);
        assertThat(callAs("-", "GET", "/api/orders", null).status()).isEqualTo(200);
    }

    @Test
    void m1MissingTenantIsTheFirst400BeforeIdempotencyAndNotFound() throws Exception {
        long p = product(100, 10);
        String key = uniq();
        assertThat(order("u", key, items(p, 1)).status()).isEqualTo(201);
        // would be 422 (key reused with another body) and 404 (unknown product) with a tenant
        assertProblem(orderIn(null, "u", key, items(999_999_999L, 1)), 400, "VALIDATION_ERROR");
        assertProblem(callAs(null, "GET", "/api/orders/999999999", null), 400, "VALIDATION_ERROR");
        assertProblem(callAs("BAD", "POST", "/api/orders/999999999/cancel", null), 400, "VALIDATION_ERROR");
        // body parse errors and the tenant header are both in the 400 stage
        assertProblem(callAs(null, "POST", "/api/products", "{bad json"), 400, "VALIDATION_ERROR");
        assertThat(call("GET", "/api/products/" + p, null).body().get("reserved").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ M2

    @Test
    void m2OtherTenantsDataIsIndistinguishableFromMissingAndUntouched() throws Exception {
        String a = tenant();
        String b = tenant();
        long p = productIn(a, 1000, 10);
        String code = code();
        assertThat(couponIn(a, code, 5).status()).isEqualTo(201);
        Res created = orderIn(a, "u", uniq(), items(p, 2, code));
        assertThat(created.status()).isEqualTo(201);
        long o = created.body().get("id").asLong();
        long paid = orderIn(a, "u2", uniq(), items(p, 1)).body().get("id").asLong();
        assertThat(payIn(a, paid, uniq(), "ok").status()).isEqualTo(200);

        assertProblem(callAs(b, "GET", "/api/products/" + p, null), 404, "PRODUCT_NOT_FOUND");
        assertProblem(callAs(b, "GET", "/api/coupons/" + code, null), 404, "COUPON_NOT_FOUND");
        assertProblem(callAs(b, "GET", "/api/orders/" + o, null), 404, "ORDER_NOT_FOUND");
        int calls = paymentCalls.get();
        assertProblem(payIn(b, o, uniq(), "ok"), 404, "ORDER_NOT_FOUND");
        assertThat(paymentCalls.get()).isEqualTo(calls);
        assertProblem(callAs(b, "POST", "/api/orders/" + o + "/cancel", null), 404, "ORDER_NOT_FOUND");
        assertProblem(callAs(b, "POST", "/api/orders/" + paid + "/cancel", null), 404, "ORDER_NOT_FOUND");
        assertProblem(callAs(b, "POST", "/api/orders/" + paid + "/ship", null), 404, "ORDER_NOT_FOUND");
        assertProblem(callAs(b, "POST", "/api/orders/" + paid + "/deliver", null), 404, "ORDER_NOT_FOUND");

        // the same responses as for ids that do not exist at all
        assertProblem(callAs(b, "GET", "/api/products/999999999", null), 404, "PRODUCT_NOT_FOUND");
        assertProblem(callAs(b, "GET", "/api/orders/999999999", null), 404, "ORDER_NOT_FOUND");

        // A's data did not change
        JsonNode prod = callAs(a, "GET", "/api/products/" + p, null).body();
        assertThat(prod.get("stock").asLong()).isEqualTo(9);
        assertThat(prod.get("reserved").asLong()).isEqualTo(2);
        assertThat(callAs(a, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isEqualTo(1);
        assertThat(callAs(a, "GET", "/api/orders/" + o, null).body().get("status").asText())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(callAs(a, "GET", "/api/orders/" + paid, null).body().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void m2OrderWithOtherTenantsProductOrCouponIs404AndReservesNothing() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = productIn(a, 1000, 10);
        long pb = productIn(b, 1000, 10);
        String code = code();
        assertThat(couponIn(a, code, 5).status()).isEqualTo(201);

        assertProblem(orderIn(b, "u", uniq(), items(pa, 1)), 404, "PRODUCT_NOT_FOUND");
        assertProblem(orderIn(b, "u", uniq(),
                "{\"items\":[{\"productId\":" + pb + ",\"quantity\":1},{\"productId\":" + pa + ",\"quantity\":1}]}"),
                404, "PRODUCT_NOT_FOUND");
        assertProblem(orderIn(b, "u", uniq(), items(pb, 1, code)), 404, "COUPON_NOT_FOUND");
        // 404 comes before 409 even when B's own product is short of stock
        assertProblem(orderIn(b, "u", uniq(), items(pb, 11, code)), 404, "COUPON_NOT_FOUND");

        assertThat(callAs(a, "GET", "/api/products/" + pa, null).body().get("reserved").asLong()).isZero();
        assertThat(callAs(b, "GET", "/api/products/" + pb, null).body().get("reserved").asLong()).isZero();
        assertThat(callAs(a, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isZero();
        assertThat(callAs(b, "GET", "/api/orders", null).body().get("content")).isEmpty();
    }

    @Test
    void m2LocationIsReadableWithTheSameTenant() throws Exception {
        String a = tenant();
        String b = tenant();
        Res p = callAs(a, "POST", "/api/products", "{\"name\":\"p\",\"price\":10,\"stock\":5}");
        Res c = couponIn(a, code(), 3);
        Res o = orderIn(a, "u", uniq(), items(p.body().get("id").asLong(), 1));
        for (Res created : List.of(p, c, o)) {
            assertThat(created.status()).isEqualTo(201);
            Res same = callAs(a, "GET", created.location(), null);
            assertThat(same.status()).isEqualTo(200);
            assertThat(callAs(b, "GET", created.location(), null).status()).isEqualTo(404);
        }
        assertThat(callAs(a, "GET", o.location(), null).body()).isEqualTo(o.body());
    }

    // ------------------------------------------------------------------ M3

    @Test
    void m3CouponCodeIsUniquePerTenantWithIndependentCounters() throws Exception {
        String a = tenant();
        String b = tenant();
        String code = code();
        Res ca = couponIn(a, code, 1);
        Res cb = couponIn(b, code, 7);
        assertThat(ca.status()).isEqualTo(201);
        assertThat(cb.status()).isEqualTo(201);
        assertProblem(couponIn(a, code, 3), 409, "DUPLICATE_COUPON_CODE");
        assertProblem(couponIn(b, code, 3), 409, "DUPLICATE_COUPON_CODE");

        long pa = productIn(a, 1000, 10);
        long pb = productIn(b, 1000, 10);
        assertThat(orderIn(a, "u1", uniq(), items(pa, 1, code)).status()).isEqualTo(201);
        assertProblem(orderIn(a, "u2", uniq(), items(pa, 1, code)), 409, "COUPON_EXHAUSTED");

        JsonNode inA = callAs(a, "GET", "/api/coupons/" + code, null).body();
        JsonNode inB = callAs(b, "GET", "/api/coupons/" + code, null).body();
        assertThat(inA.get("totalQuantity").asLong()).isEqualTo(1);
        assertThat(inA.get("usedCount").asLong()).isEqualTo(1);
        assertThat(inB.get("totalQuantity").asLong()).isEqualTo(7);
        assertThat(inB.get("usedCount").asLong()).isZero();

        // B's coupon with the same code is still usable and counts separately
        assertThat(orderIn(b, "u2", uniq(), items(pb, 1, code)).status()).isEqualTo(201);
        assertThat(callAs(b, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isEqualTo(1);
        assertThat(callAs(a, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ M4

    @Test
    void m4SameUserIdInAnotherTenantIsAnotherUser() throws Exception {
        String a = tenant();
        String b = tenant();
        String code = code();
        assertThat(couponIn(a, code, 5).status()).isEqualTo(201);
        assertThat(couponIn(b, code, 5).status()).isEqualTo(201);
        long pa = productIn(a, 1000, 10);
        long pb = productIn(b, 1000, 10);

        assertThat(orderIn(a, "same-user", uniq(), items(pa, 1, code)).status()).isEqualTo(201);
        assertThat(orderIn(b, "same-user", uniq(), items(pb, 1, code)).status()).isEqualTo(201);
        assertProblem(orderIn(a, "same-user", uniq(), items(pa, 1, code)), 409, "COUPON_NOT_APPLICABLE");
        assertProblem(orderIn(b, "same-user", uniq(), items(pb, 1, code)), 409, "COUPON_NOT_APPLICABLE");

        // cancelling in A gives the use back in A only
        long oa = callAs(a, "GET", "/api/orders?userId=same-user", null).body().get("content").get(0).get("id")
                .asLong();
        assertThat(callAs(a, "POST", "/api/orders/" + oa + "/cancel", null).status()).isEqualTo(200);
        assertThat(orderIn(a, "same-user", uniq(), items(pa, 1, code)).status()).isEqualTo(201);
        assertProblem(orderIn(b, "same-user", uniq(), items(pb, 1, code)), 409, "COUPON_NOT_APPLICABLE");
    }

    // ------------------------------------------------------------------ M5

    @Test
    void m5IdempotencyKeySpaceIsPerTenantAndPerEndpoint() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = productIn(a, 1000, 10);
        long pb = productIn(b, 1000, 10);
        String key = "shared-key";

        Res first = orderIn(a, "u", key, items(pa, 1));
        Res other = orderIn(b, "u", key, items(pb, 2));
        assertThat(first.status()).isEqualTo(201);
        assertThat(other.status()).isEqualTo(201);
        assertThat(other.body().get("id").asLong()).isNotEqualTo(first.body().get("id").asLong());
        assertThat(other.body().get("items").get(0).get("quantity").asLong()).isEqualTo(2);

        // replay / mismatch stay within each tenant
        assertThat(orderIn(a, "u", key, items(pa, 1)).body()).isEqualTo(first.body());
        assertThat(orderIn(b, "u", key, items(pb, 2)).body()).isEqualTo(other.body());
        assertProblem(orderIn(a, "u", key, items(pa, 3)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(callAs(a, "GET", "/api/products/" + pa, null).body().get("reserved").asLong()).isEqualTo(1);
        assertThat(callAs(b, "GET", "/api/products/" + pb, null).body().get("reserved").asLong()).isEqualTo(2);

        // payment: same key in both tenants (and the same key as order creation) -> each processed
        long oa = first.body().get("id").asLong();
        long ob = other.body().get("id").asLong();
        Res paidA = payIn(a, oa, key, "ok");
        Res paidB = payIn(b, ob, key, "ok");
        assertThat(paidA.status()).isEqualTo(200);
        assertThat(paidB.status()).isEqualTo(200);
        assertThat(paidA.body().get("id").asLong()).isEqualTo(oa);
        assertThat(paidB.body().get("id").asLong()).isEqualTo(ob);
        assertThat(payIn(a, oa, key, "ok").body()).isEqualTo(paidA.body());

        // the same request (same user, same body) with the same key in another tenant is not a replay
        String c = tenant();
        Res inC = orderIn(c, "u", key, items(pa, 1));
        assertProblem(inC, 404, "PRODUCT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ M6

    @Test
    void m6PgIdempotencyKeyIsTenantColonClientKey() throws Exception {
        long p = productIn("acme", 1000, 10);
        long o = orderIn("acme", "u", uniq(), items(p, 1)).body().get("id").asLong();
        int before = paymentKeys.size();
        assertThat(payIn("acme", o, "k1", "ok").status()).isEqualTo(200);
        assertThat(paymentKeys.subList(before, paymentKeys.size())).containsExactly("acme:k1");

        String other = tenant();
        long q = productIn(other, 1000, 10);
        long o2 = orderIn(other, "u", uniq(), items(q, 1)).body().get("id").asLong();
        before = paymentKeys.size();
        assertThat(payIn(other, o2, "k1", "ok").status()).isEqualTo(200);
        assertThat(paymentKeys.subList(before, paymentKeys.size())).containsExactly(other + ":k1");
    }

    // ------------------------------------------------------------------ M7

    @Test
    void m7ListReturnsOnlyTheRequestTenantsOrdersIncludingAcrossPages() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = productIn(a, 100, 100);
        long pb = productIn(b, 100, 100);
        Set<Long> aIds = new HashSet<>();
        Set<Long> bIds = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            aIds.add(orderIn(a, i % 2 == 0 ? "u" : "v", uniq(), items(pa, 1)).body().get("id").asLong());
            bIds.add(orderIn(b, "u", uniq(), items(pb, 1)).body().get("id").asLong());
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        String firstCursor = null;
        do {
            Res page = callAs(a, "GET", "/api/orders?size=2" + (cursor == null ? "" : "&cursor=" + cursor), null);
            assertThat(page.status()).isEqualTo(200);
            page.body().get("content").forEach(n -> seen.add(n.get("id").asLong()));
            cursor = page.body().get("nextCursor").isNull() ? null : page.body().get("nextCursor").asText();
            if (firstCursor == null) {
                firstCursor = cursor;
            }
            // a new order of another tenant between pages changes nothing
            orderIn(b, "u", uniq(), items(pb, 1));
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(aIds);

        Res userU = callAs(a, "GET", "/api/orders?userId=u&size=100", null);
        assertThat(userU.body().get("content")).hasSize(3);
        userU.body().get("content").forEach(n -> {
            assertThat(aIds).contains(n.get("id").asLong());
            assertThat(n.get("userId").asText()).isEqualTo("u");
        });

        // a cursor obtained in A only positions B's own list
        Res withForeignCursor = callAs(b, "GET", "/api/orders?size=100&cursor=" + firstCursor, null);
        assertThat(withForeignCursor.status()).isEqualTo(200);
        withForeignCursor.body().get("content")
                .forEach(n -> assertThat(aIds).doesNotContain(n.get("id").asLong()));

        Res inC = callAs(tenant(), "GET", "/api/orders?userId=u", null);
        assertThat(inC.body().get("content")).isEmpty();
        assertThat(inC.body().get("nextCursor").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ M8

    List<Integer> runAll(ExecutorService pool, List<Callable<Integer>> tasks) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            out.add(f.get());
        }
        return out;
    }

    @Test
    void m8SameCouponCodeInTwoTenantsUnderConcurrency() throws Exception {
        String a = tenant();
        String b = tenant();
        String code = code();
        assertThat(couponIn(a, code, 5).status()).isEqualTo(201);
        assertThat(couponIn(b, code, 5).status()).isEqualTo(201);
        long pa = productIn(a, 1000, 1000);
        long pb = productIn(b, 1000, 1000);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String user = "user-" + i;
            tasks.add(() -> orderIn(a, user, uniq(), items(pa, 1, code)).status() * 10 + 1);
            tasks.add(() -> orderIn(b, user, uniq(), items(pb, 1, code)).status() * 10 + 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(30);
        try {
            List<Integer> results = runAll(pool, tasks);
            assertThat(results).filteredOn(r -> r == 2011).hasSize(5);
            assertThat(results).filteredOn(r -> r == 2012).hasSize(5);
            assertThat(results).filteredOn(r -> r == 4091).hasSize(10);
            assertThat(results).filteredOn(r -> r == 4092).hasSize(10);
        } finally {
            pool.shutdown();
        }
        assertThat(callAs(a, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isEqualTo(5);
        assertThat(callAs(b, "GET", "/api/coupons/" + code, null).body().get("usedCount").asLong()).isEqualTo(5);
    }

    @Test
    void m8StockAndOneCouponPerUserHoldPerTenant() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = productIn(a, 100, 10);
        long pb = productIn(b, 100, 10);
        String code = code();
        assertThat(couponIn(a, code, 50).status()).isEqualTo(201);
        assertThat(couponIn(b, code, 50).status()).isEqualTo(201);
        long qa = productIn(a, 1000, 1000);
        long qb = productIn(b, 1000, 1000);

        ExecutorService pool = Executors.newFixedThreadPool(30);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tasks.add(() -> orderIn(a, "c", uniq(), items(pa, 1)).status() * 10 + 1);
                tasks.add(() -> orderIn(b, "c", uniq(), items(pb, 1)).status() * 10 + 2);
            }
            List<Integer> results = runAll(pool, tasks);
            assertThat(results).filteredOn(r -> r == 2011).hasSize(10);
            assertThat(results).filteredOn(r -> r == 2012).hasSize(10);
            assertThat(results).filteredOn(r -> r == 4091).hasSize(10);
            assertThat(results).filteredOn(r -> r == 4092).hasSize(10);
            assertThat(callAs(a, "GET", "/api/products/" + pa, null).body().get("reserved").asLong()).isEqualTo(10);
            assertThat(callAs(b, "GET", "/api/products/" + pb, null).body().get("reserved").asLong()).isEqualTo(10);

            // one user, same coupon code, 5 concurrent orders in each tenant -> exactly one per tenant
            tasks.clear();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> orderIn(a, "same", uniq(), items(qa, 1, code)).status() * 10 + 1);
                tasks.add(() -> orderIn(b, "same", uniq(), items(qb, 1, code)).status() * 10 + 2);
            }
            results = runAll(pool, tasks);
            assertThat(results).filteredOn(r -> r == 2011).hasSize(1);
            assertThat(results).filteredOn(r -> r == 2012).hasSize(1);
            assertThat(results).allMatch(r -> r / 10 == 201 || r / 10 == 409);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void m8SameIdempotencyKeyConcurrentlyInTwoTenantsCreatesTwoOrders() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String a = tenant();
                String b = tenant();
                long pa = productIn(a, 100, 10);
                long pb = productIn(b, 100, 10);
                String key = "same-key-" + round;
                List<Callable<Res>> tasks = List.of(
                        () -> orderIn(a, "u", key, items(pa, 1)),
                        () -> orderIn(b, "u", key, items(pb, 1)));
                List<Res> results = new ArrayList<>();
                for (Future<Res> f : pool.invokeAll(tasks)) {
                    results.add(f.get());
                }
                assertThat(results).allMatch(r -> r.status() == 201);
                assertThat(results.get(0).body().get("id").asLong())
                        .isNotEqualTo(results.get(1).body().get("id").asLong());
                assertThat(callAs(a, "GET", "/api/orders", null).body().get("content")).hasSize(1);
                assertThat(callAs(b, "GET", "/api/orders", null).body().get("content")).hasSize(1);
            }
        } finally {
            pool.shutdown();
        }
    }
}
