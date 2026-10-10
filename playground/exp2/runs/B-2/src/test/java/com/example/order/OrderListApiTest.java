package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** R9. 주문 목록 */
class OrderListApiTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void filtersByUser_newestFirst_withSameShapeAsDetail() {
        long p = createProduct(1_000, 100);
        String user = uniqueUser();
        long first = placeOrder(user, null, item(p, 1));
        long second = placeOrder(user, null, item(p, 2));
        placeOrder(uniqueUser(), null, item(p, 1));

        Res res = get("/api/orders?userId=" + user);

        assertThat(res.status()).isEqualTo(200);
        JsonNode content = res.body().get("content");
        assertThat(ids(content)).containsExactly(second, first);
        assertThat(content.get(0)).isEqualTo(order(second));
        assertThat(res.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void filtersByUserAndStatus() {
        long p = createProduct(1_000, 100);
        String user = uniqueUser();
        long cancelled = placeOrder(user, null, item(p, 1));
        post("/api/orders/" + cancelled + "/cancel");
        long pending = placeOrder(user, null, item(p, 1));
        placeOrder(uniqueUser(), null, item(p, 1));

        assertThat(ids(get("/api/orders?userId=" + user + "&status=CANCELLED").body().get("content"))).containsExactly(cancelled);
        assertThat(ids(get("/api/orders?userId=" + user + "&status=PENDING_PAYMENT").body().get("content"))).containsExactly(pending);

        JsonNode allCancelled = get("/api/orders?status=CANCELLED&size=100").body().get("content");
        allCancelled.forEach(o -> assertThat(o.get("status").asText()).isEqualTo("CANCELLED"));
        assertThat(ids(allCancelled)).contains(cancelled);
    }

    @Test
    void defaultSizeIs20() {
        String user = insertOrders(21, Instant.now());

        Res first = get("/api/orders?userId=" + user);
        assertThat(first.body().get("content")).hasSize(20);
        assertThat(first.body().get("nextCursor").isNull()).isFalse();

        Res second = get("/api/orders?userId=" + user + "&cursor=" + first.body().get("nextCursor").asText());
        assertThat(second.body().get("content")).hasSize(1);
        assertThat(second.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidQuery_returns400() {
        String validCursor = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-01-01T00:00:00Z|1".getBytes());
        assertThat(get("/api/orders?size=1&cursor=" + validCursor).status()).isEqualTo(200);
        assertThat(get("/api/orders?size=100").status()).isEqualTo(200);

        for (String query : List.of("size=0", "size=101", "size=abc", "status=UNKNOWN", "status=paid",
                "cursor=%25%25%25", "cursor=bm90LWEtY3Vyc29y")) {
            assertProblem(get("/api/orders?" + query), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void sortsByCreatedAtDesc_thenIdDesc_acrossPages() {
        Instant sameTime = Instant.parse("2026-03-01T00:00:00Z");
        String user = insertOrders(5, sameTime);
        List<Long> expected = jdbcTemplate.queryForList(
                "select id from orders where user_id = ? order by id desc", Long.class, user);

        assertThat(collectAllPages(user, 2)).containsExactlyElementsOf(expected);
    }

    @Test
    void ordersExistingAtFirstPage_appearExactlyOnce_evenIfNewOrdersArrive() {
        long p = createProduct(1_000, 1_000);
        String user = uniqueUser();
        List<Long> existing = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            existing.addFirst(placeOrder(user, null, item(p, 1)));
        }

        List<Long> seen = new ArrayList<>();
        Res page = get("/api/orders?userId=" + user + "&size=3");
        seen.addAll(ids(page.body().get("content")));
        while (!page.body().get("nextCursor").isNull()) {
            placeOrder(user, null, item(p, 1)); // 페이지 사이에 새 주문이 생긴다
            page = get("/api/orders?userId=" + user + "&size=3&cursor=" + page.body().get("nextCursor").asText());
            seen.addAll(ids(page.body().get("content")));
        }

        assertThat(seen).containsExactlyElementsOf(existing);
    }

    private List<Long> collectAllPages(String user, int size) {
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            Res page = get("/api/orders?userId=" + user + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            seen.addAll(ids(page.body().get("content")));
            cursor = page.body().get("nextCursor").isNull() ? null : page.body().get("nextCursor").asText();
        } while (cursor != null);
        return seen;
    }

    /** createdAt을 직접 지정해야 하는 경우를 위해 DB에 주문을 바로 넣는다. */
    private String insertOrders(int count, Instant createdAt) {
        String user = uniqueUser();
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    insert into orders (user_id, status, subtotal, discount, total_price, created_at, expires_at)
                    values (?, 'CANCELLED', 0, 0, 0, ?, ?)
                    """, user, Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(900)));
        }
        return user;
    }

    private static List<Long> ids(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }
}
