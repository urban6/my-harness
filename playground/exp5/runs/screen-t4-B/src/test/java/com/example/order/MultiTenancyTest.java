package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Multi-tenancy change request, M1-M8. Every test uses fresh tenants so tests cannot see each other's data. */
class MultiTenancyTest extends IntegrationTestBase {

    record Outcome(String tenant, int status, Res res) {
    }

    static String tenant() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static String items(long productId, long quantity, String couponCode) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]"
                + (couponCode == null ? "" : ",\"couponCode\":\"" + couponCode + "\"") + "}";
    }

    Res coupon(String tenant, String code, long totalQuantity) throws Exception {
        return callAs(tenant, "POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"FIXED\",\"value\":100,"
                + "\"totalQuantity\":" + totalQuantity + ",\"validFrom\":\"" + Instant.now().minusSeconds(60)
                + "\",\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}");
    }

    JsonNode getCoupon(String tenant, String code) throws Exception {
        return callAs(tenant, "GET", "/api/coupons/" + code, null).body();
    }

    JsonNode getProduct(String tenant, long id) throws Exception {
        return callAs(tenant, "GET", "/api/products/" + id, null).body();
    }

    JsonNode getOrder(String tenant, long id) throws Exception {
        return callAs(tenant, "GET", "/api/orders/" + id, null).body();
    }

    long orderId(Res created) {
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asLong();
    }

    List<Long> ids(Res list) {
        List<Long> ids = new ArrayList<>();
        list.body().get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }

    void assertError(Res res, int status, String code) {
        assertThat(res.status()).isEqualTo(status);
        assertThat(res.contentType()).startsWith("application/problem+json");
        assertThat(res.body().get("code").asText()).isEqualTo(code);
    }

    List<Outcome> runAll(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Outcome> out = new ArrayList<>();
            for (Future<Outcome> f : pool.invokeAll(tasks)) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    long count(List<Outcome> outcomes, String tenant, int status) {
        return outcomes.stream().filter(o -> o.tenant().equals(tenant) && o.status() == status).count();
    }

    // ------------------------------------------------------------------------------------------ M1

    @Test
    void m1_everyApiRequestNeedsAWellFormedTenantHeader() throws Exception {
        // missing header: reads, writes, order actions and lists alike
        Res[] missing = {
                rawCall("GET", "/api/products/1", null),
                rawCall("POST", "/api/products", "{\"name\":\"p\",\"price\":100,\"stock\":1}"),
                rawCall("GET", "/api/coupons/ABCD", null),
                rawCall("GET", "/api/orders", null),
                rawCall("GET", "/api/orders/1", null),
                rawCall("POST", "/api/orders/1/cancel", null),
                rawCall("POST", "/api/orders/1/ship", null),
                rawCall("POST", "/api/orders/1/deliver", null),
                rawCall("POST", "/api/orders/1/pay", "{\"cardToken\":\"ok\"}", "Idempotency-Key", "k"),
                rawCall("POST", "/api/orders", items(1, 1, null), "X-User-Id", "u", "Idempotency-Key", "k"),
        };
        for (Res r : missing) {
            assertError(r, 400, "VALIDATION_ERROR");
        }

        // malformed header: upper case, underscore, blank, empty, space, 31 characters
        for (String bad : new String[] {"ACME", "a_b", " ", "", "a b", "a".repeat(31)}) {
            assertError(rawCall("GET", "/api/orders", null, "X-Tenant-Id", bad), 400, "VALIDATION_ERROR");
        }

        // lower case letters, digits and hyphens, 1-30 characters
        for (String good : new String[] {"a", "-", "0", "acme-2", "a".repeat(30)}) {
            assertThat(rawCall("GET", "/api/orders", null, "X-Tenant-Id", good).status()).isEqualTo(200);
        }

        // 400 comes before 404 and before the idempotency checks (C3)
        assertError(rawCall("GET", "/api/orders/999999999", null), 400, "VALIDATION_ERROR");
        assertError(rawCall("POST", "/api/orders", items(999999999, 1, "NOPE1234"),
                "X-User-Id", "u", "Idempotency-Key", "k", "X-Tenant-Id", "BAD"), 400, "VALIDATION_ERROR");
        assertError(callAs("t-ok", "GET", "/api/orders/999999999", null), 404, "ORDER_NOT_FOUND");
    }

    // ------------------------------------------------------------------------------------------ M2

    @Test
    void m2_otherTenantsDataBehavesAsIfItDidNotExist() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = product(a, 1000, 10);
        assertThat(coupon(a, "SHARED01", 5).status()).isEqualTo(201);
        long id = orderId(order(a, "u1", uniq(), items(pa, 2, "SHARED01")));

        // M2.2: reads, pay, cancel, ship, deliver
        assertError(callAs(b, "GET", "/api/products/" + pa, null), 404, "PRODUCT_NOT_FOUND");
        assertError(callAs(b, "GET", "/api/coupons/SHARED01", null), 404, "COUPON_NOT_FOUND");
        assertError(callAs(b, "GET", "/api/orders/" + id, null), 404, "ORDER_NOT_FOUND");
        int pgCalls = paymentCalls.get();
        assertError(pay(b, id, uniq(), "ok"), 404, "ORDER_NOT_FOUND");
        assertThat(paymentCalls.get()).isEqualTo(pgCalls);
        for (String action : new String[] {"cancel", "ship", "deliver"}) {
            assertError(callAs(b, "POST", "/api/orders/" + id + "/" + action, null), 404, "ORDER_NOT_FOUND");
        }
        assertThat(getOrder(a, id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(getProduct(a, pa).get("reserved").asLong()).isEqualTo(2);
        assertThat(getCoupon(a, "SHARED01").get("usedCount").asLong()).isEqualTo(1);

        // the same on a PAID order: the other tenant can neither refund nor ship it
        assertThat(pay(a, id, uniq(), "ok").status()).isEqualTo(200);
        for (String action : new String[] {"cancel", "ship", "deliver"}) {
            assertError(callAs(b, "POST", "/api/orders/" + id + "/" + action, null), 404, "ORDER_NOT_FOUND");
        }
        assertThat(getOrder(a, id).get("status").asText()).isEqualTo("PAID");
        JsonNode prod = getProduct(a, pa);
        assertThat(prod.get("stock").asLong()).isEqualTo(8);
        assertThat(prod.get("reserved").asLong()).isZero();
        assertThat(getCoupon(a, "SHARED01").get("usedCount").asLong()).isEqualTo(1);
    }

    @Test
    void m2_orderCreationWithAnotherTenantsProductOrCouponReservesNothing() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = product(a, 1000, 10);
        long pb = product(b, 500, 5);
        assertThat(coupon(a, "ONLYA001", 5).status()).isEqualTo(201);

        assertError(order(b, "u1", uniq(), items(pa, 1, null)), 404, "PRODUCT_NOT_FOUND");
        assertError(order(b, "u1", uniq(),
                "{\"items\":[{\"productId\":" + pb + ",\"quantity\":1},{\"productId\":" + pa + ",\"quantity\":1}]}"),
                404, "PRODUCT_NOT_FOUND");
        assertError(order(b, "u1", uniq(), items(pb, 1, "ONLYA001")), 404, "COUPON_NOT_FOUND");

        assertThat(getProduct(b, pb).get("reserved").asLong()).isZero();
        assertThat(getProduct(a, pa).get("reserved").asLong()).isZero();
        assertThat(getCoupon(a, "ONLYA001").get("usedCount").asLong()).isZero();
        assertThat(callAs(b, "GET", "/api/orders", null).body().get("content")).isEmpty();
        assertThat(callAs(a, "GET", "/api/orders", null).body().get("content")).isEmpty();
    }

    @Test
    void m2_locationIsReadableWithTheSameTenantHeader() throws Exception {
        String a = tenant();
        String b = tenant();
        Res p = callAs(a, "POST", "/api/products", "{\"name\":\"p\",\"price\":100,\"stock\":5}");
        Res c = coupon(a, "LOCATE01", 3);
        Res o = order(a, "u1", uniq(), items(p.body().get("id").asLong(), 1, null));
        for (Res created : new Res[] {p, c, o}) {
            assertThat(created.status()).isEqualTo(201);
            assertThat(callAs(a, "GET", created.location(), null).status()).isEqualTo(200);
            assertThat(callAs(b, "GET", created.location(), null).status()).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------------------------------ M3

    @Test
    void m3_couponCodeIsUniquePerTenantAndCountersAreIndependent() throws Exception {
        String a = tenant();
        String b = tenant();
        String c = tenant();
        assertThat(coupon(a, "SAMECODE", 3).status()).isEqualTo(201);
        assertThat(coupon(b, "SAMECODE", 7).status()).isEqualTo(201);
        assertError(coupon(a, "SAMECODE", 3), 409, "DUPLICATE_COUPON_CODE");
        assertError(coupon(b, "SAMECODE", 7), 409, "DUPLICATE_COUPON_CODE");

        long pa = product(a, 1000, 10);
        long pb = product(b, 1000, 10);
        assertThat(order(a, "u1", uniq(), items(pa, 1, "SAMECODE")).status()).isEqualTo(201);
        assertThat(order(b, "u1", uniq(), items(pb, 1, "SAMECODE")).status()).isEqualTo(201);
        assertThat(order(b, "u2", uniq(), items(pb, 1, "SAMECODE")).status()).isEqualTo(201);

        JsonNode ca = getCoupon(a, "SAMECODE");
        JsonNode cb = getCoupon(b, "SAMECODE");
        assertThat(ca.get("totalQuantity").asLong()).isEqualTo(3);
        assertThat(ca.get("usedCount").asLong()).isEqualTo(1);
        assertThat(cb.get("totalQuantity").asLong()).isEqualTo(7);
        assertThat(cb.get("usedCount").asLong()).isEqualTo(2);

        // a tenant without the coupon cannot use or read it
        assertError(callAs(c, "GET", "/api/coupons/SAMECODE", null), 404, "COUPON_NOT_FOUND");
        assertError(order(c, "u1", uniq(), items(product(c, 100, 5), 1, "SAMECODE")), 404, "COUPON_NOT_FOUND");
    }

    // ------------------------------------------------------------------------------------------ M4

    @Test
    void m4_sameUserIdInAnotherTenantIsAnotherUser() throws Exception {
        String a = tenant();
        String b = tenant();
        assertThat(coupon(a, "USERS001", 5).status()).isEqualTo(201);
        assertThat(coupon(b, "USERS001", 5).status()).isEqualTo(201);
        long pa = product(a, 1000, 10);
        long pb = product(b, 1000, 10);

        long inA = orderId(order(a, "shared-user", uniq(), items(pa, 1, "USERS001")));
        long inB = orderId(order(b, "shared-user", uniq(), items(pb, 1, "USERS001")));

        // R2.5 still holds inside each tenant
        assertError(order(a, "shared-user", uniq(), items(pa, 1, "USERS001")), 409, "COUPON_NOT_APPLICABLE");
        assertError(order(b, "shared-user", uniq(), items(pb, 1, "USERS001")), 409, "COUPON_NOT_APPLICABLE");

        // freeing the coupon in A does not free it in B
        assertThat(callAs(a, "POST", "/api/orders/" + inA + "/cancel", null).status()).isEqualTo(200);
        assertThat(getCoupon(a, "USERS001").get("usedCount").asLong()).isZero();
        assertThat(getCoupon(b, "USERS001").get("usedCount").asLong()).isEqualTo(1);
        assertThat(order(a, "shared-user", uniq(), items(pa, 1, "USERS001")).status()).isEqualTo(201);
        assertError(order(b, "shared-user", uniq(), items(pb, 1, "USERS001")), 409, "COUPON_NOT_APPLICABLE");
        assertThat(getOrder(b, inB).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    // ------------------------------------------------------------------------------------------ M5

    @Test
    void m5_idempotencyKeysAreScopedPerTenant() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = product(a, 1000, 10);
        long pb = product(b, 1000, 10);
        String key = uniq();

        // same key, different requests, different tenants: two separate orders, no 422
        Res ra = order(a, "u1", key, items(pa, 1, null));
        Res rb = order(b, "u9", key, items(pb, 3, null));
        long idA = orderId(ra);
        long idB = orderId(rb);
        assertThat(idA).isNotEqualTo(idB);
        assertThat(order(a, "u1", key, items(pa, 1, null)).body()).isEqualTo(ra.body());
        assertThat(order(b, "u9", key, items(pb, 3, null)).body()).isEqualTo(rb.body());
        // inside one tenant the key is still bound to its first request (R4.3)
        assertError(order(a, "u1", key, items(pa, 2, null)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(getProduct(a, pa).get("reserved").asLong()).isEqualTo(1);
        assertThat(getProduct(b, pb).get("reserved").asLong()).isEqualTo(3);

        // payments: same key in both tenants is two payments
        String payKey = uniq();
        int pgCalls = paymentCalls.get();
        Res paidA = pay(a, idA, payKey, "ok");
        Res paidB = pay(b, idB, payKey, "ok");
        assertThat(paidA.status()).isEqualTo(200);
        assertThat(paidB.status()).isEqualTo(200);
        assertThat(paidA.body().get("id").asLong()).isEqualTo(idA);
        assertThat(paidB.body().get("id").asLong()).isEqualTo(idB);
        assertThat(paymentCalls.get() - pgCalls).isEqualTo(2);
        // replay stays within the tenant and does not call the PG again
        assertThat(pay(a, idA, payKey, "ok").body()).isEqualTo(paidA.body());
        assertThat(pay(b, idB, payKey, "ok").body()).isEqualTo(paidB.body());
        assertThat(paymentCalls.get() - pgCalls).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------ M6

    @Test
    void m6_pgIdempotencyKeyIsQualifiedWithTheTenant() throws Exception {
        long pAcme = product("acme", 1000, 10);
        long pGlobex = product("globex", 1000, 10);
        long acme = orderId(order("acme", "u1", "k1", items(pAcme, 1, null)));
        long globex = orderId(order("globex", "u1", "k1", items(pGlobex, 1, null)));

        assertThat(pay("acme", acme, "k1", "ok").status()).isEqualTo(200);
        assertThat(pgPaymentKeys).contains("acme:k1").doesNotContain("k1");
        assertThat(pay("globex", globex, "k1", "ok").status()).isEqualTo(200);
        assertThat(pgPaymentKeys).contains("globex:k1").doesNotContain("k1");

        // the rest of the PG contract is unchanged: decline still maps to 402 and restores the order
        long declined = orderId(order("acme", "u2", uniq(), items(pAcme, 1, null)));
        assertError(pay("acme", declined, "k2", "decline"), 402, "PAYMENT_DECLINED");
        assertThat(pgPaymentKeys).contains("acme:k2");
        assertThat(getOrder("acme", declined).get("status").asText()).isEqualTo("PAYMENT_FAILED");
    }

    // ------------------------------------------------------------------------------------------ M7

    @Test
    void m7_orderListContainsOnlyTheRequestingTenantsOrders() throws Exception {
        String a = tenant();
        String b = tenant();
        String c = tenant();
        long pa = product(a, 1000, 100);
        long pb = product(b, 1000, 100);
        List<Long> idsA = new ArrayList<>();
        List<Long> idsB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            idsA.add(orderId(order(a, "u1", uniq(), items(pa, 1, null))));
        }
        idsA.add(orderId(order(a, "u2", uniq(), items(pa, 1, null))));
        for (int i = 0; i < 2; i++) {
            idsB.add(orderId(order(b, "u1", uniq(), items(pb, 1, null))));
        }

        Res listA = callAs(a, "GET", "/api/orders", null);
        assertThat(ids(listA)).containsExactlyInAnyOrderElementsOf(idsA);
        assertThat(ids(callAs(b, "GET", "/api/orders", null))).containsExactlyInAnyOrderElementsOf(idsB);
        Res none = callAs(c, "GET", "/api/orders", null);
        assertThat(none.body().get("content")).isEmpty();
        assertThat(none.body().get("nextCursor").isNull()).isTrue();

        // userId filter applies inside the tenant
        assertThat(ids(callAs(a, "GET", "/api/orders?userId=u1", null))).hasSize(3).isSubsetOf(idsA);
        assertThat(ids(callAs(b, "GET", "/api/orders?userId=u1", null))).containsExactlyInAnyOrderElementsOf(idsB);
        assertThat(callAs(b, "GET", "/api/orders?userId=u2", null).body().get("content")).isEmpty();

        // keyset pagination stays inside the tenant
        Res page1 = callAs(a, "GET", "/api/orders?size=3", null);
        String cursor = page1.body().get("nextCursor").asText();
        Res page2 = callAs(a, "GET", "/api/orders?size=3&cursor=" + cursor, null);
        List<Long> paged = new ArrayList<>(ids(page1));
        paged.addAll(ids(page2));
        assertThat(paged).containsExactlyInAnyOrderElementsOf(idsA).doesNotContainAnyElementsOf(idsB);
        assertThat(page2.body().get("nextCursor").isNull()).isTrue();

        // status filter too
        assertThat(pay(a, idsA.get(0), uniq(), "ok").status()).isEqualTo(200);
        assertThat(ids(callAs(a, "GET", "/api/orders?status=PAID", null))).containsExactly(idsA.get(0));
        assertThat(callAs(b, "GET", "/api/orders?status=PAID", null).body().get("content")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ M8

    @Test
    void m8_stockRaceHoldsInEveryTenant() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            for (String t : new String[] {a, b}) {
                long p = t.equals(a) ? pa : pb;
                tasks.add(() -> {
                    Res r = order(t, "c-" + uniq(), uniq(), items(p, 1, null));
                    return new Outcome(t, r.status(), r);
                });
            }
        }
        List<Outcome> out = runAll(tasks);
        for (String t : new String[] {a, b}) {
            assertThat(count(out, t, 201)).isEqualTo(10);
            assertThat(count(out, t, 409)).isEqualTo(10);
        }
        assertThat(getProduct(a, pa).get("reserved").asLong()).isEqualTo(10);
        assertThat(getProduct(b, pb).get("reserved").asLong()).isEqualTo(10);
    }

    @Test
    void m8_sameCouponCodeInTwoTenantsIsExhaustedIndependently() throws Exception {
        String a = tenant();
        String b = tenant();
        assertThat(coupon(a, "RACE0005", 5).status()).isEqualTo(201);
        assertThat(coupon(b, "RACE0005", 5).status()).isEqualTo(201);
        long pa = product(a, 100, 1000);
        long pb = product(b, 100, 1000);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String user = "user-" + i; // the same 15 user ids in both tenants
            for (String t : new String[] {a, b}) {
                long p = t.equals(a) ? pa : pb;
                tasks.add(() -> {
                    Res r = order(t, user, uniq(), items(p, 1, "RACE0005"));
                    return new Outcome(t, r.status(), r);
                });
            }
        }
        List<Outcome> out = runAll(tasks);
        for (String t : new String[] {a, b}) {
            assertThat(count(out, t, 201)).isEqualTo(5);
            assertThat(count(out, t, 409)).isEqualTo(10);
            assertThat(getCoupon(t, "RACE0005").get("usedCount").asLong()).isEqualTo(5);
        }
    }

    @Test
    void m8_oneUserOneCouponRaceHoldsInEveryTenant() throws Exception {
        String a = tenant();
        String b = tenant();
        assertThat(coupon(a, "SOLO0001", 10).status()).isEqualTo(201);
        assertThat(coupon(b, "SOLO0001", 10).status()).isEqualTo(201);
        long pa = product(a, 100, 1000);
        long pb = product(b, 100, 1000);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            for (String t : new String[] {a, b}) {
                long p = t.equals(a) ? pa : pb;
                tasks.add(() -> {
                    Res r = order(t, "same-user", uniq(), items(p, 1, "SOLO0001"));
                    return new Outcome(t, r.status(), r);
                });
            }
        }
        List<Outcome> out = runAll(tasks);
        for (String t : new String[] {a, b}) {
            assertThat(count(out, t, 201)).isEqualTo(1);
            assertThat(count(out, t, 409)).isEqualTo(4);
            assertThat(getCoupon(t, "SOLO0001").get("usedCount").asLong()).isEqualTo(1);
        }
    }

    @Test
    void m8_sameIdempotencyKeyInTwoTenantsConcurrentlyCreatesTwoOrders() throws Exception {
        String a = tenant();
        String b = tenant();
        long pa = product(a, 100, 1000);
        long pb = product(b, 100, 1000);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String key = uniq(); // each key is used by both tenants at the same moment
            for (String t : new String[] {a, b}) {
                long p = t.equals(a) ? pa : pb;
                tasks.add(() -> {
                    Res r = order(t, "u1", key, items(p, 1, null));
                    return new Outcome(t, r.status(), r);
                });
            }
        }
        List<Outcome> out = runAll(tasks);
        assertThat(out).allSatisfy(o -> assertThat(o.status()).isEqualTo(201));
        Set<Long> created = new HashSet<>();
        out.forEach(o -> created.add(o.res().body().get("id").asLong()));
        assertThat(created).hasSize(20);
        assertThat(callAs(a, "GET", "/api/orders?size=100", null).body().get("content")).hasSize(10);
        assertThat(callAs(b, "GET", "/api/orders?size=100", null).body().get("content")).hasSize(10);
        assertThat(getProduct(a, pa).get("reserved").asLong()).isEqualTo(10);
        assertThat(getProduct(b, pb).get("reserved").asLong()).isEqualTo(10);
    }
}
