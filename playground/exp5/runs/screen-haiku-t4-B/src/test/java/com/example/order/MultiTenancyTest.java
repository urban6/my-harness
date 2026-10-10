package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** M1~M8: tenant header, data isolation, coupon codes, users, idempotency, PG, listing, concurrency. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiTenancyTest extends IntegrationTestBase {

    /** Each test gets its own tenants: the database is shared by all tests in the JVM. */
    static String newTenant() {
        return "t" + UUID.randomUUID().toString().substring(0, 12);
    }

    static String newCode() {
        return "CP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    static String body(long productId, long quantity) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    static String body(long productId, long quantity, String couponCode) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}],\"couponCode\":\""
                + couponCode + "\"}";
    }

    static void assertProblem(Res r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.body().get("code").asText()).isEqualTo(code);
        assertThat(r.contentType()).startsWith("application/problem+json");
    }

    static Set<Long> ids(JsonNode page) {
        Set<Long> out = new HashSet<>();
        page.get("content").forEach(o -> out.add(o.get("id").asLong()));
        return out;
    }

    long reserved(String tenant, long productId) throws Exception {
        return send("GET", "/api/products/" + productId, null, tenant).body().get("reserved").asLong();
    }

    long usedCount(String tenant, String code) throws Exception {
        return send("GET", "/api/coupons/" + code, null, tenant).body().get("usedCount").asLong();
    }

    // ---------------------------------------------------------------- M1. tenant header

    @Test
    @DisplayName("M1.1 /api 요청은 모두 X-Tenant-Id가 필수다")
    void tenantHeaderIsRequiredOnEveryApiRequest() throws Exception {
        assertProblem(send("GET", "/api/products/1", null, null), 400, "VALIDATION_ERROR");
        assertProblem(send("POST", "/api/products", "{\"name\":\"p\",\"price\":1,\"stock\":1}", null), 400,
                "VALIDATION_ERROR");
        assertProblem(send("POST", "/api/coupons", "{}", null), 400, "VALIDATION_ERROR");
        assertProblem(send("POST", "/api/orders", body(1, 1), null, "X-User-Id", "u", "Idempotency-Key", uniq()),
                400, "VALIDATION_ERROR");
        assertProblem(send("GET", "/api/orders?size=2", null, null), 400, "VALIDATION_ERROR");
        assertProblem(send("POST", "/api/orders/1/pay", "{\"cardToken\":\"ok\"}", null, "Idempotency-Key", uniq()),
                400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("M1.2 형식이 틀린 X-Tenant-Id는 400이고, 존재 검사(404)보다 먼저 판정된다")
    void malformedTenantIsRejectedBeforeLookup() throws Exception {
        for (String bad : List.of("ACME", "a_b", "a.b", "x".repeat(31), "")) {
            Res r = send("GET", "/api/orders/999999999", null, bad);
            assertThat(r.status()).as("X-Tenant-Id '%s'", bad).isEqualTo(400);
            assertThat(r.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        String max = "a".repeat(30);
        Res created = send("POST", "/api/products", "{\"name\":\"p\",\"price\":1,\"stock\":1}", max);
        assertThat(created.status()).isEqualTo(201);
        assertThat(send("GET", "/api/orders/999999999", null, max).body().get("code").asText())
                .isEqualTo("ORDER_NOT_FOUND");
    }

    // ---------------------------------------------------------------- M2. data isolation

    @Test
    @DisplayName("M2.2 다른 테넌트의 상품·주문은 존재하지 않는 것과 같고, 조회·결제·취소·배송이 모두 404다")
    void otherTenantRowsBehaveAsMissing() throws Exception {
        String a = newTenant();
        String b = newTenant();
        long p = product(a, 100, 10);
        long id = orderAs(a, "u", uniq(), body(p, 2)).body().get("id").asLong();
        int before = paymentCalls.get();

        assertProblem(send("GET", "/api/products/" + p, null, b), 404, "PRODUCT_NOT_FOUND");
        assertProblem(send("GET", "/api/orders/" + id, null, b), 404, "ORDER_NOT_FOUND");
        assertProblem(payAs(b, id, uniq(), "ok"), 404, "ORDER_NOT_FOUND");
        assertProblem(send("POST", "/api/orders/" + id + "/cancel", null, b), 404, "ORDER_NOT_FOUND");
        assertProblem(send("POST", "/api/orders/" + id + "/ship", null, b), 404, "ORDER_NOT_FOUND");
        assertProblem(send("POST", "/api/orders/" + id + "/deliver", null, b), 404, "ORDER_NOT_FOUND");
        assertThat(paymentCalls.get()).isEqualTo(before);

        assertThat(send("GET", "/api/orders/" + id, null, a).body().get("status").asText())
                .isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(a, p)).isEqualTo(2);
        assertThat(send("GET", "/api/products/" + p, null, a).body().get("stock").asLong()).isEqualTo(10);
    }

    @Test
    @DisplayName("M2.3 다른 테넌트의 상품·쿠폰을 지정한 주문은 404이고 아무것도 예약되지 않는다")
    void foreignProductOrCouponIsNotFoundAndReservesNothing() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String code = newCode();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);
        couponAs(a, code, 5);

        assertProblem(orderAs(b, "u", uniq(), body(pa, 1)), 404, "PRODUCT_NOT_FOUND");
        assertProblem(orderAs(b, "u", uniq(),
                "{\"items\":[{\"productId\":" + pb + ",\"quantity\":1},{\"productId\":" + pa + ",\"quantity\":1}]}"),
                404, "PRODUCT_NOT_FOUND");
        assertProblem(orderAs(b, "u", uniq(), body(pb, 1, code)), 404, "COUPON_NOT_FOUND");

        assertThat(reserved(b, pb)).isZero();
        assertThat(reserved(a, pa)).isZero();
        assertThat(usedCount(a, code)).isZero();
    }

    @Test
    @DisplayName("M2.4 응답의 Location은 같은 테넌트 헤더로 조회된다")
    void locationIsReadableWithTheSameTenant() throws Exception {
        String a = newTenant();
        long p = product(a, 100, 10);
        Res created = orderAs(a, "u", uniq(), body(p, 1));
        assertThat(created.status()).isEqualTo(201);

        Res read = send("GET", created.location(), null, a);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().get("id").asLong()).isEqualTo(created.body().get("id").asLong());
        assertProblem(send("GET", created.location(), null, newTenant()), 404, "ORDER_NOT_FOUND");
    }

    // ---------------------------------------------------------------- M3. coupon code

    @Test
    @DisplayName("M3.1 쿠폰 code는 테넌트 안에서만 유일하다")
    void couponCodeIsUniquePerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String code = newCode();
        assertThat(couponAs(a, code, 5).status()).isEqualTo(201);
        assertThat(couponAs(b, code, 5).status()).isEqualTo(201);
        assertProblem(couponAs(a, code, 5), 409, "DUPLICATE_COUPON_CODE");
    }

    @Test
    @DisplayName("M3.2 같은 code라도 usedCount·totalQuantity는 테넌트별로 독립이다")
    void couponCountersAreIndependentPerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String code = newCode();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);
        couponAs(a, code, 1);
        couponAs(b, code, 3);
        assertThat(orderAs(a, "u1", uniq(), body(pa, 1, code)).status()).isEqualTo(201);

        JsonNode couponA = send("GET", "/api/coupons/" + code, null, a).body();
        JsonNode couponB = send("GET", "/api/coupons/" + code, null, b).body();
        assertThat(couponA.get("usedCount").asLong()).isEqualTo(1);
        assertThat(couponA.get("totalQuantity").asLong()).isEqualTo(1);
        assertThat(couponB.get("usedCount").asLong()).isZero();
        assertThat(couponB.get("totalQuantity").asLong()).isEqualTo(3);

        // A is used up (1 of 1), B still has room
        assertProblem(orderAs(a, "u2", uniq(), body(pa, 1, code)), 409, "COUPON_EXHAUSTED");
        assertThat(orderAs(b, "u2", uniq(), body(pb, 1, code)).status()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- M4. users

    @Test
    @DisplayName("M4.2 쿠폰의 '같은 사용자 활성 주문 없음' 조건은 테넌트별로 판단한다")
    void sameUserCouponRuleIsPerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String code = newCode();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);
        couponAs(a, code, 5);
        couponAs(b, code, 5);

        assertThat(orderAs(a, "same", uniq(), body(pa, 1, code)).status()).isEqualTo(201);
        assertProblem(orderAs(a, "same", uniq(), body(pa, 1, code)), 409, "COUPON_NOT_APPLICABLE");
        // 같은 X-User-Id라도 테넌트 B에서는 활성 주문이 없으므로 쓸 수 있다
        assertThat(orderAs(b, "same", uniq(), body(pb, 1, code)).status()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- M5. idempotency

    @Test
    @DisplayName("M5.1 같은 Idempotency-Key라도 테넌트가 다르면 재생이나 422가 아니라 각각 처리한다")
    void idempotencyKeySpaceIsPerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String key = uniq();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);

        Res first = orderAs(a, "u", key, body(pa, 1));
        assertThat(first.status()).isEqualTo(201);
        Res other = orderAs(b, "u", key, body(pb, 2));
        assertThat(other.status()).isEqualTo(201);
        assertThat(other.body().get("id").asLong()).isNotEqualTo(first.body().get("id").asLong());
        assertThat(other.body().get("totalPrice").asLong()).isEqualTo(200);

        // 같은 테넌트 안에서는 여전히 같은 키·다른 본문은 422, 같은 요청은 재생
        assertProblem(orderAs(a, "u", key, body(pa, 2)), 422, "IDEMPOTENCY_KEY_MISMATCH");
        assertThat(orderAs(a, "u", key, body(pa, 1)).body()).isEqualTo(first.body());
    }

    // ---------------------------------------------------------------- M6. external PG

    @Test
    @DisplayName("M6.1 PG 요청의 Idempotency-Key는 {tenantId}:{Idempotency-Key}다")
    void pgIdempotencyKeyIsNamespacedByTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        long oa = orderAs(a, "u", uniq(), body(product(a, 1000, 10), 1)).body().get("id").asLong();
        long ob = orderAs(b, "u", uniq(), body(product(b, 1000, 10), 1)).body().get("id").asLong();
        int before = pgCalls.size();

        assertThat(payAs(a, oa, "k1", "ok").status()).isEqualTo(200);
        assertThat(payAs(b, ob, "k1", "ok").status()).isEqualTo(200);

        assertThat(pgCalls.subList(before, pgCalls.size())).extracting(PgCall::idempotencyKey)
                .containsExactly(a + ":k1", b + ":k1");
    }

    @Test
    @DisplayName("M6.2 PG 결제 요청 본문은 {orderId, amount, cardToken} 그대로다")
    void pgPaymentBodyIsUnchanged() throws Exception {
        String a = newTenant();
        long p = product(a, 1000, 10);
        long id = orderAs(a, "u", uniq(), body(p, 2)).body().get("id").asLong();
        int before = pgCalls.size();

        assertThat(payAs(a, id, "k2", "ok").status()).isEqualTo(200);

        JsonNode sent = om.readTree(pgCalls.get(before).body());
        assertThat(sent.size()).isEqualTo(3);
        assertThat(sent.get("orderId").asLong()).isEqualTo(id);
        assertThat(sent.get("amount").asLong()).isEqualTo(2000);
        assertThat(sent.get("cardToken").asText()).isEqualTo("ok");
    }

    // ---------------------------------------------------------------- M7. listing

    @Test
    @DisplayName("M4.1·M7.1 주문 목록과 userId 필터는 요청 테넌트 안에서만 동작하고, 같은 X-User-Id는 테넌트마다 다른 사용자다")
    void listingStaysInsideTheTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String user = "shared-" + uniq();
        long pa = product(a, 100, 100);
        long pb = product(b, 100, 100);
        Set<Long> aIds = new HashSet<>();
        Set<Long> bIds = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            aIds.add(orderAs(a, user, uniq(), body(pa, 1)).body().get("id").asLong());
        }
        for (int i = 0; i < 2; i++) {
            bIds.add(orderAs(b, user, uniq(), body(pb, 1)).body().get("id").asLong());
        }

        JsonNode firstPage = send("GET", "/api/orders?userId=" + user + "&size=2", null, a).body();
        assertThat(ids(firstPage)).hasSize(2).isSubsetOf(aIds);
        JsonNode secondPage = send("GET", "/api/orders?userId=" + user + "&size=2&cursor="
                + firstPage.get("nextCursor").asText(), null, a).body();
        assertThat(secondPage.get("content")).hasSize(1);
        Set<Long> paged = new HashSet<>(ids(firstPage));
        paged.addAll(ids(secondPage));
        assertThat(paged).isEqualTo(aIds);

        assertThat(ids(send("GET", "/api/orders?userId=" + user, null, b).body())).isEqualTo(bIds);
        assertThat(ids(send("GET", "/api/orders?status=PENDING_PAYMENT", null, a).body())).isEqualTo(aIds);
        assertThat(ids(send("GET", "/api/orders", null, b).body())).isEqualTo(bIds);
    }

    // ---------------------------------------------------------------- M8. concurrency

    @Test
    @DisplayName("M8.1 R10 동시성 규칙(재고 초과 없음)은 테넌트마다 성립한다")
    void concurrencyRulesHoldPerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        long pa = product(a, 100, 10);
        long pb = product(b, 100, 10);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> orderAs(a, "c", uniq(), body(pa, 1)));
        }
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> orderAs(b, "c", uniq(), body(pb, 1)));
        }

        List<Res> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(40)) {
            for (Future<Res> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
        }
        for (List<Res> perTenant : List.of(results.subList(0, 20), results.subList(20, 40))) {
            assertThat(perTenant).filteredOn(r -> r.status() == 201).hasSize(10);
            List<Res> conflicts = perTenant.stream().filter(r -> r.status() == 409).toList();
            assertThat(conflicts).hasSize(10);
            assertThat(conflicts).allSatisfy(r -> assertThat(r.body().get("code").asText())
                    .isEqualTo("INSUFFICIENT_STOCK"));
        }
        assertThat(reserved(a, pa)).isEqualTo(10);
        assertThat(reserved(b, pb)).isEqualTo(10);
    }

    @Test
    @DisplayName("M8.2 같은 code·총수량 5인 쿠폰을 두 테넌트가 동시에 15건씩 써도 테넌트마다 정확히 5건만 201이다")
    void sameCouponCodeUnderConcurrencyIsCountedPerTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String code = newCode();
        long pa = product(a, 100, 1000);
        long pb = product(b, 100, 1000);
        couponAs(a, code, 5);
        couponAs(b, code, 5);

        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String user = "u" + i;   // distinct users: only the quantity limit applies
            tasks.add(() -> orderAs(a, user, uniq(), body(pa, 1, code)));
            tasks.add(() -> orderAs(b, user, uniq(), body(pb, 1, code)));
        }

        List<Res> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(30)) {
            for (Future<Res> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
        }
        // tasks alternate a, b, a, b, ...
        List<Res> forA = new ArrayList<>();
        List<Res> forB = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            (i % 2 == 0 ? forA : forB).add(results.get(i));
        }
        for (List<Res> perTenant : List.of(forA, forB)) {
            assertThat(perTenant).filteredOn(r -> r.status() == 201).hasSize(5);
            List<Res> exhausted = perTenant.stream().filter(r -> r.status() == 409).toList();
            assertThat(exhausted).hasSize(10);
            assertThat(exhausted).allSatisfy(r -> assertThat(r.body().get("code").asText())
                    .isEqualTo("COUPON_EXHAUSTED"));
        }
        assertThat(usedCount(a, code)).isEqualTo(5);
        assertThat(usedCount(b, code)).isEqualTo(5);
    }

    @Test
    @DisplayName("M8.3 두 테넌트가 같은 Idempotency-Key로 동시에 주문하면 둘 다 201이고 주문은 2건이다")
    void sameIdempotencyKeyConcurrentlyAcrossTenants() throws Exception {
        for (int round = 0; round < 5; round++) {
            String a = newTenant();
            String b = newTenant();
            String key = uniq();
            long pa = product(a, 100, 10);
            long pb = product(b, 100, 10);
            List<Callable<Res>> tasks = List.of(
                    () -> orderAs(a, "u", key, body(pa, 1)),
                    () -> orderAs(b, "u", key, body(pb, 1)));

            List<Res> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Future<Res> f : pool.invokeAll(tasks)) {
                    results.add(f.get());
                }
            }
            assertThat(results).extracting(Res::status).containsExactly(201, 201);
            assertThat(results.get(0).body().get("id").asLong())
                    .isNotEqualTo(results.get(1).body().get("id").asLong());
            assertThat(reserved(a, pa)).isEqualTo(1);
            assertThat(reserved(b, pb)).isEqualTo(1);
        }
    }
}
