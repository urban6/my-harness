package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.FakePaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** change-request.md P1-P6: points account, points on orders, PG amounts, partial refunds, changed behaviour, races. */
// close the context with this class: its expiry sweeper must not outlive the container it talks to
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PointsAndPartialRefundTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final FakePaymentGateway pg = new FakePaymentGateway();

    @AfterAll
    static void stopPg() {
        pg.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("PAYMENT_GATEWAY_URL", () -> "http://127.0.0.1:" + pg.port());
        r.add("ORDER_PAYMENT_TTL", () -> "PT6S");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper om = new ObjectMapper();

    record Res(int status, JsonNode body) {
    }

    Res call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = res.body().isEmpty() ? null : om.readTree(res.body());
        return new Res(res.statusCode(), json);
    }

    long product(long price, long stock) throws Exception {
        Res r = call("POST", "/api/products", "{\"name\":\"p\",\"price\":" + price + ",\"stock\":" + stock + "}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    /** {"items":[{"productId":p,"quantity":q},...]} from alternating productId, quantity values, plus extra fields. */
    String orderBody(String extra, long... productQuantity) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < productQuantity.length; i += 2) {
            lines.add("{\"productId\":" + productQuantity[i] + ",\"quantity\":" + productQuantity[i + 1] + "}");
        }
        return "{\"items\":[" + String.join(",", lines) + "]" + extra + "}";
    }

    Res order(String user, String key, String body) throws Exception {
        return call("POST", "/api/orders", body, "X-User-Id", user, "Idempotency-Key", key);
    }

    long createOrder(String user, String body) throws Exception {
        Res r = order(user, uniq(), body);
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    Res pay(long orderId, String key, String token) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + token + "\"}",
                "Idempotency-Key", key);
    }

    Res refund(long orderId, String key, String body) throws Exception {
        return call("POST", "/api/orders/" + orderId + "/refunds", body, "Idempotency-Key", key);
    }

    Res grant(String user, long amount) throws Exception {
        return call("POST", "/api/points/" + user + "/grants", "{\"amount\":" + amount + "}");
    }

    long balance(String user) throws Exception {
        return call("GET", "/api/points/" + user, null).body().get("balance").asLong();
    }

    JsonNode getOrder(long orderId) throws Exception {
        return call("GET", "/api/orders/" + orderId, null).body();
    }

    String statusOf(long orderId) throws Exception {
        return getOrder(orderId).get("status").asText();
    }

    long stockOf(long productId) throws Exception {
        return call("GET", "/api/products/" + productId, null).body().get("stock").asLong();
    }

    long couponUsed(String code) throws Exception {
        return call("GET", "/api/coupons/" + code, null).body().get("usedCount").asLong();
    }

    /** A RATE coupon with no minimum order amount and no cap, valid now. */
    String rateCoupon(long percent, long totalQuantity) throws Exception {
        String code = "PT" + Long.toString(System.nanoTime() % 1_000_000_000_000L, 36).toUpperCase();
        Res c = call("POST", "/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"RATE\",\"value\":" + percent
                + ",\"totalQuantity\":" + totalQuantity + ",\"validFrom\":\"" + Instant.now().minusSeconds(60)
                + "\",\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        assertThat(c.status()).isEqualTo(201);
        return code;
    }

    JsonNode itemOf(JsonNode order, long productId) {
        for (JsonNode item : order.get("items")) {
            if (item.get("productId").asLong() == productId) {
                return item;
            }
        }
        throw new AssertionError("no item for product " + productId);
    }

    String uniq() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ P1 points account

    @Test
    void p1_grantAndBalance() throws Exception {
        String user = "p1-" + uniq();
        assertThat(balance(user)).isZero(); // never granted: balance 0, not 404 (P1.3)

        Res g = grant(user, 1000);
        assertThat(g.status()).isEqualTo(200);
        assertThat(g.body().get("userId").asText()).isEqualTo(user);
        assertThat(g.body().get("balance").asLong()).isEqualTo(1000);
        assertThat(grant(user, 500).body().get("balance").asLong()).isEqualTo(1500);
        assertThat(balance(user)).isEqualTo(1500);
    }

    @Test
    void p1_grantValidation() throws Exception {
        String user = "p1v-" + uniq();
        for (String body : List.of("{\"amount\":0}", "{\"amount\":10000001}", "{\"amount\":1.5}",
                "{\"amount\":\"100\"}", "{}", "{bad json")) {
            Res r = call("POST", "/api/points/" + user + "/grants", body);
            assertThat(r.status()).as(body).isEqualTo(400);
            assertThat(r.body().get("code").asText()).as(body).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(call("POST", "/api/points/%20/grants", "{\"amount\":1}").status()).isEqualTo(400);
        assertThat(call("GET", "/api/points/" + "x".repeat(51), null).status()).isEqualTo(400);

        // upper bound is inclusive
        assertThat(grant(user, 10_000_000).status()).isEqualTo(200);
        assertThat(balance(user)).isEqualTo(10_000_000);
    }

    // ------------------------------------------------------------------ P2 points on orders

    @Test
    void p2_usePointsOnOrders() throws Exception {
        String user = "p2-" + uniq();
        grant(user, 1000);
        long p = product(5000, 100);

        // P2.1: usePoints must be a non-negative integer when present
        assertThat(order(user, uniq(), orderBody(",\"usePoints\":-1", p, 1)).status()).isEqualTo(400);
        assertThat(order(user, uniq(), orderBody(",\"usePoints\":1.5", p, 1)).status()).isEqualTo(400);

        // P2.2: above the total is POINTS_EXCEED_TOTAL, and that comes before the balance check
        Res tooMany = order(user, uniq(), orderBody(",\"usePoints\":5001", p, 1));
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.body().get("code").asText()).isEqualTo("POINTS_EXCEED_TOTAL");

        // above the balance (but not above the total) is INSUFFICIENT_POINTS
        Res noBalance = order(user, uniq(), orderBody(",\"usePoints\":1001", p, 1));
        assertThat(noBalance.status()).isEqualTo(409);
        assertThat(noBalance.body().get("code").asText()).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(balance(user)).isEqualTo(1000);

        // P2.3: stock is checked before points
        long soldOut = product(5000, 0);
        assertThat(order(user, uniq(), orderBody(",\"usePoints\":5001", soldOut, 1)).body().get("code").asText())
                .isEqualTo("INSUFFICIENT_STOCK");

        // P2.4-P2.5: the balance is spent on creation, and the response carries the split
        String withPoints = orderBody(",\"usePoints\":600", p, 1);
        String key = uniq();
        Res created = order(user, key, withPoints);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("pointAmount").asLong()).isEqualTo(600);
        assertThat(created.body().get("cardAmount").asLong()).isEqualTo(4400);
        assertThat(created.body().get("refundedAmount").asLong()).isZero();
        assertThat(itemOf(created.body(), p).get("refundedQuantity").asLong()).isZero();
        assertThat(balance(user)).isEqualTo(400);
        long withPointsId = created.body().get("id").asLong();

        // P2.7: same key, other usePoints is another request; the same request replays without spending again
        assertThat(order(user, key, orderBody(",\"usePoints\":500", p, 1)).status()).isEqualTo(422);
        assertThat(order(user, key, withPoints).body()).isEqualTo(created.body());
        assertThat(balance(user)).isEqualTo(400);

        // usePoints omitted means 0
        Res plain = order(user, uniq(), orderBody("", p, 1));
        assertThat(plain.body().get("pointAmount").asLong()).isZero();
        assertThat(plain.body().get("cardAmount").asLong()).isEqualTo(5000);

        // P2.6: cancelling a pending order gives its points back
        assertThat(call("POST", "/api/orders/" + withPointsId + "/cancel", null).body().get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(balance(user)).isEqualTo(1000);

        // P3.3 / P2.6: a declined payment makes the order PAYMENT_FAILED and gives the points back
        long declined = createOrder(user, orderBody(",\"usePoints\":300", p, 1));
        assertThat(balance(user)).isEqualTo(700);
        assertThat(pay(declined, uniq(), "decline").status()).isEqualTo(402);
        assertThat(statusOf(declined)).isEqualTo("PAYMENT_FAILED");
        assertThat(balance(user)).isEqualTo(1000);

        // P2.6 + R6.2: an expired order gives its points back within 2 s of expiresAt
        long expiring = createOrder(user, orderBody(",\"usePoints\":250", p, 1));
        assertThat(balance(user)).isEqualTo(750);
        Thread.sleep(7000); // ORDER_PAYMENT_TTL is PT6S
        assertThat(statusOf(expiring)).isEqualTo("EXPIRED");
        assertThat(balance(user)).isEqualTo(1000);
    }

    // ------------------------------------------------------------------ P3 amounts sent to the PG

    @Test
    void p3_pgChargesCardAmountAndSkipsZeroCard() throws Exception {
        String user = "p3-" + uniq();
        grant(user, 10_000);
        long p = product(5000, 10);

        // P3.1: the PG is asked for cardAmount = total - points
        String key = uniq();
        long partial = createOrder(user, orderBody(",\"usePoints\":1000", p, 1));
        assertThat(pay(partial, key, "ok").status()).isEqualTo(200);
        assertThat(pg.chargedFor(key)).isEqualTo(4000);

        // P3.2: nothing left for the card, so the order is approved without a PG call
        long full = createOrder(user, orderBody(",\"usePoints\":5000", p, 1));
        int before = pg.paymentCount();
        Res paidFull = pay(full, uniq(), "decline"); // would be declined if the PG were called
        assertThat(paidFull.status()).isEqualTo(200);
        assertThat(paidFull.body().get("status").asText()).isEqualTo("PAID");
        assertThat(pg.paymentCount()).isEqualTo(before);

        // P3.3: PG outage leaves the order pending and the points still spent
        long outage = createOrder(user, orderBody(",\"usePoints\":1000", p, 1));
        assertThat(pay(outage, uniq(), "fail").status()).isEqualTo(503);
        assertThat(statusOf(outage)).isEqualTo("PENDING_PAYMENT");
        assertThat(balance(user)).isEqualTo(3000);
    }

    // ------------------------------------------------------------------ P4 partial refunds

    @Test
    void p4_refundSplitsCardFirstThenPoints() throws Exception {
        String user = "p4-" + uniq();
        grant(user, 1000);
        long a = product(1000, 10);
        long b = product(3000, 10);
        long id = createOrder(user, orderBody(",\"usePoints\":1000", a, 2, b, 1)); // subtotal 5000, card 4000
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);
        assertThat(balance(user)).isZero();

        // P4.5-P4.6: one A (1000) is paid by the card
        String k1 = uniq();
        Res r1 = refund(id, k1, orderBody("", a, 1));
        assertThat(r1.status()).isEqualTo(200);
        assertThat(r1.body().get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(r1.body().get("refundedAmount").asLong()).isEqualTo(1000);
        assertThat(itemOf(r1.body(), a).get("refundedQuantity").asLong()).isEqualTo(1);
        assertThat(pg.refundedFor(k1)).isEqualTo(1000);
        assertThat(stockOf(a)).isEqualTo(9); // 10 - 2 sold + 1 back

        // one B (3000): cumulative 4000 of 5000, the card still has 3000 left
        String k2 = uniq();
        Res r2 = refund(id, k2, orderBody("", b, 1));
        assertThat(r2.body().get("refundedAmount").asLong()).isEqualTo(4000);
        assertThat(pg.refundedFor(k2)).isEqualTo(3000);
        assertThat(balance(user)).isZero();

        // last A (1000): the card is used up, so the points get it
        String k3 = uniq();
        Res r3 = refund(id, k3, orderBody("", a, 1));
        assertThat(r3.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(r3.body().get("refundedAmount").asLong()).isEqualTo(5000);
        assertThat(pg.refundedFor(k3)).isZero();
        assertThat(balance(user)).isEqualTo(1000);
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(10);
    }

    @Test
    void p4_cumulativeRoundingAndCouponRestore() throws Exception {
        String user = "p4r-" + uniq();
        String code = rateCoupon(10, 5);
        long x = product(10, 10);
        long y = product(7, 10);
        long id = createOrder(user, orderBody(",\"couponCode\":\"" + code + "\"", x, 1, y, 1));
        // subtotal 17, discount floor(17 x 10 / 100) = 1, total 16
        assertThat(getOrder(id).get("totalPrice").asLong()).isEqualTo(16);
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);
        assertThat(couponUsed(code)).isEqualTo(1);

        // P4.5: floor(16 x 10 / 17) = 9 for the first unit, not 10
        String k1 = uniq();
        assertThat(refund(id, k1, orderBody("", x, 1)).body().get("refundedAmount").asLong()).isEqualTo(9);
        assertThat(pg.refundedFor(k1)).isEqualTo(9);
        assertThat(couponUsed(code)).isEqualTo(1); // partly refunded: the coupon is still in use

        // the rest brings the total to exactly totalPrice; the full refund restores the coupon (R2.6)
        String k2 = uniq();
        Res last = refund(id, k2, orderBody("", y, 1));
        assertThat(last.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(last.body().get("refundedAmount").asLong()).isEqualTo(16);
        assertThat(pg.refundedFor(k2)).isEqualTo(7);
        assertThat(couponUsed(code)).isZero();
    }

    @Test
    void p4_refundValidationStatesAndIdempotency() throws Exception {
        String user = "p4e-" + uniq();
        long a = product(1000, 10);
        long b = product(1000, 10);
        long id = createOrder(user, orderBody("", a, 2, b, 1));
        String path = "/api/orders/" + id + "/refunds";

        // P4.2: 400 for a missing header, an empty or duplicated items list, or a quantity below 1
        assertThat(call("POST", path, orderBody("", a, 1)).status()).isEqualTo(400);
        assertThat(call("POST", path, "{\"items\":[]}", "Idempotency-Key", uniq()).status()).isEqualTo(400);
        assertThat(call("POST", path, orderBody("", a, 1, a, 1), "Idempotency-Key", uniq()).status()).isEqualTo(400);
        assertThat(call("POST", path, orderBody("", a, 0), "Idempotency-Key", uniq()).status()).isEqualTo(400);

        // P4.3: a pending order cannot be refunded; P4.2: unknown order is 404
        Res pending = refund(id, uniq(), orderBody("", a, 1));
        assertThat(pending.status()).isEqualTo(409);
        assertThat(pending.body().get("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(refund(999_999_999L, uniq(), orderBody("", a, 1)).body().get("code").asText())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

        // P4.4: a product not in the order, or more units than remain
        long other = product(1000, 10);
        Res notInOrder = refund(id, uniq(), orderBody("", other, 1));
        assertThat(notInOrder.status()).isEqualTo(409);
        assertThat(notInOrder.body().get("code").asText()).isEqualTo("REFUND_QUANTITY_EXCEEDED");
        Res tooMany = refund(id, uniq(), orderBody("", a, 3));
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.body().get("code").asText()).isEqualTo("REFUND_QUANTITY_EXCEEDED");
        assertThat(stockOf(a)).isEqualTo(8); // the rejected requests changed nothing

        // P4.10: the same key and request replays the first answer without refunding again; another request is 422
        String k = uniq();
        Res first = refund(id, k, orderBody("", a, 1));
        assertThat(first.status()).isEqualTo(200);
        Res replay = refund(id, k, orderBody("", a, 1));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(pg.refundedFor(k)).isEqualTo(1000);
        assertThat(refund(id, k, orderBody("", b, 1)).status()).isEqualTo(422);
    }

    @Test
    void p4_gatewayFailureChangesNothingAndKeyCanBeRetried() throws Exception {
        String user = "p4f-" + uniq();
        long a = product(1000, 10);
        long id = createOrder(user, orderBody("", a, 2));
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

        String key = uniq();
        pg.setRefundMode("fail");
        try {
            Res r = refund(id, key, orderBody("", a, 1));
            assertThat(r.status()).isEqualTo(503);
            assertThat(r.body().get("code").asText()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        } finally {
            pg.setRefundMode("ok");
        }
        JsonNode o = getOrder(id);
        assertThat(o.get("status").asText()).isEqualTo("PAID");
        assertThat(o.get("refundedAmount").asLong()).isZero();
        assertThat(itemOf(o, a).get("refundedQuantity").asLong()).isZero();
        assertThat(stockOf(a)).isEqualTo(8);

        // R4.4: the key of a request that failed can be used again for the same request
        assertThat(refund(id, key, orderBody("", a, 1)).status()).isEqualTo(200);
        assertThat(stockOf(a)).isEqualTo(9);
    }

    // ------------------------------------------------------------------ P5 changed behaviour

    @Test
    void p5_cancelOfPartlyRefundedOrder() throws Exception {
        String user = "p5-" + uniq();
        String code = rateCoupon(10, 5);
        long a = product(1000, 10);
        long b = product(1000, 10);
        long id = createOrder(user, orderBody(",\"couponCode\":\"" + code + "\"", a, 1, b, 1));
        // subtotal 2000, discount 200, total 1800
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

        assertThat(refund(id, uniq(), orderBody("", a, 1)).body().get("refundedAmount").asLong()).isEqualTo(900);

        // P5.3: the status filter accepts PARTIALLY_REFUNDED
        assertThat(call("GET", "/api/orders?userId=" + user + "&status=PARTIALLY_REFUNDED", null)
                .body().get("content")).hasSize(1);

        // a partly refunded order still holds the coupon (R2.5 / R2.6)
        assertThat(couponUsed(code)).isEqualTo(1);
        assertThat(order(user, uniq(), orderBody(",\"couponCode\":\"" + code + "\"", b, 1)).body().get("code")
                .asText()).isEqualTo("COUPON_NOT_APPLICABLE");

        // P5.1: cancel refunds the rest (1800 - 900 = 900) under the key cancel-{orderId}; a PG failure changes nothing
        pg.setRefundMode("fail");
        try {
            assertThat(call("POST", "/api/orders/" + id + "/cancel", null).status()).isEqualTo(503);
        } finally {
            pg.setRefundMode("ok");
        }
        assertThat(statusOf(id)).isEqualTo("PARTIALLY_REFUNDED");

        Res cancelled = call("POST", "/api/orders/" + id + "/cancel", null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(cancelled.body().get("refundedAmount").asLong()).isEqualTo(1800);
        assertThat(pg.refundedFor("cancel-" + id)).isEqualTo(900);
        assertThat(couponUsed(code)).isZero();
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(stockOf(b)).isEqualTo(10);
    }

    @Test
    void p5_shipAfterPartialRefundAndNoRefundAfterShipping() throws Exception {
        String user = "p5s-" + uniq();
        long a = product(1000, 10);
        long b = product(1000, 10);
        long id = createOrder(user, orderBody("", a, 2, b, 1));
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);
        assertThat(refund(id, uniq(), orderBody("", a, 1)).status()).isEqualTo(200);

        // P5.2: a partly refunded order can be shipped
        assertThat(call("POST", "/api/orders/" + id + "/ship", null).body().get("status").asText())
                .isEqualTo("SHIPPED");

        // SHIPPED orders can be neither refunded nor cancelled
        Res r = refund(id, uniq(), orderBody("", a, 1));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code").asText()).isEqualTo("INVALID_STATE");
        Res c = call("POST", "/api/orders/" + id + "/cancel", null);
        assertThat(c.status()).isEqualTo(409);
        assertThat(c.body().get("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(call("POST", "/api/orders/" + id + "/deliver", null).body().get("status").asText())
                .isEqualTo("DELIVERED");
    }

    // ------------------------------------------------------------------ P6 concurrency

    @Test
    void p6_pointBalanceIsSpentOnceUnderConcurrentOrders() throws Exception {
        String user = "p6a-" + uniq();
        grant(user, 1000);
        long p = product(1000, 100);
        String body = orderBody(",\"usePoints\":600", p, 1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> order(user, uniq(), body));
            }
            List<Res> results = new ArrayList<>();
            for (Future<Res> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
            assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
            assertThat(results).filteredOn(r -> r.status() == 409).hasSize(4)
                    .allSatisfy(r -> assertThat(r.body().get("code").asText()).isEqualTo("INSUFFICIENT_POINTS"));
            assertThat(balance(user)).isEqualTo(400);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void p6_concurrentPartialRefundsStopAtTheRemainingQuantity() throws Exception {
        String user = "p6b-" + uniq();
        long x = product(1000, 10);
        long y = product(1000, 10);
        // x keeps 3 units and y is never refunded, so the order stays PARTIALLY_REFUNDED
        long id = createOrder(user, orderBody("", x, 3, y, 1));
        assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

        List<String> keys = new ArrayList<>();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String key = uniq();
            keys.add(key);
            tasks.add(() -> refund(id, key, orderBody("", x, 1)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Res> results = new ArrayList<>();
            for (Future<Res> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
            assertThat(results).filteredOn(r -> r.status() == 200).hasSize(3);
            assertThat(results).filteredOn(r -> r.status() == 409).hasSize(2)
                    .allSatisfy(r -> assertThat(r.body().get("code").asText())
                            .isEqualTo("REFUND_QUANTITY_EXCEEDED"));
        } finally {
            pool.shutdownNow();
        }

        JsonNode o = getOrder(id);
        assertThat(itemOf(o, x).get("refundedQuantity").asLong()).isEqualTo(3);
        assertThat(o.get("refundedAmount").asLong()).isEqualTo(3000);
        assertThat(stockOf(x)).isEqualTo(10);
        // the PG refunds add up to the card amount refunded so far (no points in this order)
        assertThat(keys.stream().mapToLong(pg::refundedFor).sum()).isEqualTo(3000);
    }

    @Test
    void p6_cancelRacingPartialRefundStaysConsistent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String user = "p6c-" + uniq();
                long x = product(1000, 10);
                long y = product(1000, 10);
                long id = createOrder(user, orderBody("", x, 2, y, 1));
                assertThat(pay(id, uniq(), "ok").status()).isEqualTo(200);

                String partialKey = uniq();
                List<Callable<Res>> tasks = List.of(
                        () -> refund(id, partialKey, orderBody("", x, 1)),
                        () -> call("POST", "/api/orders/" + id + "/cancel", null));
                List<Res> results = new ArrayList<>();
                for (Future<Res> f : pool.invokeAll(tasks)) {
                    results.add(f.get());
                }
                // no 5xx: the refund lands before the cancel (200) or after it and finds the order REFUNDED (409)
                assertThat(results.get(0).status()).isIn(200, 409);
                assertThat(results.get(1).status()).isEqualTo(200);

                JsonNode o = getOrder(id);
                assertThat(o.get("status").asText()).isEqualTo("REFUNDED");
                assertThat(o.get("refundedAmount").asLong()).isEqualTo(3000);
                assertThat(itemOf(o, x).get("refundedQuantity").asLong()).isEqualTo(2);
                assertThat(itemOf(o, y).get("refundedQuantity").asLong()).isEqualTo(1);
                assertThat(stockOf(x)).isEqualTo(10);
                assertThat(stockOf(y)).isEqualTo(10);
                assertThat(pg.refundedFor(partialKey) + pg.refundedFor("cancel-" + id)).isEqualTo(3000);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
