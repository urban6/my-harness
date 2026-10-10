package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** R9. 주문 목록 */
class OrderListTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private String user() {
        return "list-" + uniq().substring(0, 10);
    }

    private long create(String user, long productId) {
        Res r = createOrder(user, "k-" + uniq(), items(productId, 1), null);
        assertThat(r.status()).isEqualTo(201);
        return r.json().get("id").asLong();
    }

    private List<Long> ids(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    /** 모든 페이지를 순서대로 따라가며 id를 모은다. */
    private List<Long> walk(String query, int size) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        do {
            Res r = get("/api/orders?" + query + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).isEqualTo(200);
            all.addAll(ids(r.json().get("content")));
            cursor = r.json().get("nextCursor").isNull() ? null : r.json().get("nextCursor").asText();
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("R9.1·R9.4 createdAt 내림차순, 원소는 주문 조회와 같은 형태, 마지막 페이지의 nextCursor는 null")
    void listNewestFirst() {
        long p = product(1000, 100);
        String user = user();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(create(user, p));
        }

        Res r = get("/api/orders?userId=" + user);

        assertThat(r.status()).isEqualTo(200);
        assertThat(ids(r.json().get("content"))).containsExactly(created.get(2), created.get(1), created.get(0));
        assertThat(r.json().get("nextCursor").isNull()).isTrue();
        assertThat(r.json().get("content").get(0)).isEqualTo(orderOf(created.get(2)));
    }

    @Test
    @DisplayName("R9.4 createdAt이 같으면 id 내림차순, 페이지 경계에서도 중복·누락 없음")
    void tieBreakById() {
        long p = product(1000, 100);
        String user = user();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(create(user, p));
        }
        jdbc.update("update orders set created_at = ? where user_id = ?", Timestamp.from(Instant.now()), user);

        List<Long> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected); // id 내림차순
        assertThat(walk("userId=" + user, 5)).containsExactlyElementsOf(expected);
        assertThat(walk("userId=" + user, 2)).containsExactlyElementsOf(expected);
        assertThat(walk("userId=" + user, 1)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("R9.1 다음 페이지가 있을 때만 nextCursor가 있고, 딱 맞아떨어지면 null")
    void nextCursorOnlyWhenMore() {
        long p = product(1000, 100);
        String user = user();
        for (int i = 0; i < 4; i++) {
            create(user, p);
        }

        Res first = get("/api/orders?userId=" + user + "&size=3");
        assertThat(first.json().get("content")).hasSize(3);
        assertThat(first.json().get("nextCursor").isNull()).isFalse();

        Res second = get("/api/orders?userId=" + user + "&size=3&cursor=" + first.json().get("nextCursor").asText());
        assertThat(second.json().get("content")).hasSize(1);
        assertThat(second.json().get("nextCursor").isNull()).isTrue();

        Res exact = get("/api/orders?userId=" + user + "&size=4");
        assertThat(exact.json().get("content")).hasSize(4);
        assertThat(exact.json().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.2 userId·status 필터, 둘을 주면 AND")
    void filters() {
        long p = product(1000, 100);
        String alice = user();
        String bob = user();
        long a1 = create(alice, p);
        long a2 = create(alice, p);
        long b1 = create(bob, p);
        assertThat(post("/api/orders/" + a2 + "/cancel", null).status()).isEqualTo(200);
        assertThat(pay(b1).status()).isEqualTo(200);

        assertThat(ids(get("/api/orders?userId=" + alice).json().get("content"))).containsExactly(a2, a1);
        assertThat(ids(get("/api/orders?userId=" + alice + "&status=CANCELLED").json().get("content")))
                .containsExactly(a2);
        assertThat(ids(get("/api/orders?userId=" + alice + "&status=PENDING_PAYMENT").json().get("content")))
                .containsExactly(a1);
        assertThat(ids(get("/api/orders?userId=" + bob + "&status=CANCELLED").json().get("content"))).isEmpty();
        assertThat(ids(get("/api/orders?userId=" + bob + "&status=PAID").json().get("content"))).containsExactly(b1);
        // userId 없이 status만: 이 사용자의 주문이 포함되어야 한다.
        assertThat(walk("status=PAID", 100)).contains(b1).doesNotContain(a1, a2);
    }

    @Test
    @DisplayName("R9.2 정의되지 않은 status는 400")
    void unknownStatus() {
        for (String status : List.of("UNKNOWN", "paid", "")) {
            if (status.isEmpty()) {
                continue;
            }
            Res r = get("/api/orders?status=" + status);
            assertThat(r.status()).as(status).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R9.3 size 기본 20, 1~100, 범위 밖·숫자가 아니면 400")
    void sizeRules() {
        long p = product(1000, 100);
        String user = user();
        for (int i = 0; i < 22; i++) {
            create(user, p);
        }

        Res defaults = get("/api/orders?userId=" + user);
        assertThat(defaults.json().get("content")).hasSize(20);
        assertThat(defaults.json().get("nextCursor").isNull()).isFalse();
        assertThat(get("/api/orders?userId=" + user + "&size=1").json().get("content")).hasSize(1);
        assertThat(get("/api/orders?userId=" + user + "&size=100").json().get("content")).hasSize(22);

        for (String size : List.of("0", "-1", "101", "abc", "1.5")) {
            Res r = get("/api/orders?userId=" + user + "&size=" + size);
            assertThat(r.status()).as("size=" + size).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R9.3 해석할 수 없는 cursor는 400")
    void invalidCursor() {
        for (String cursor : List.of("garbage", "!!!", "MTIz", "bm90LWEtY3Vyc29y")) {
            Res r = get("/api/orders?cursor=" + cursor);
            assertThat(r.status()).as("cursor=" + cursor).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 기존 주문은 정확히 한 번씩 나온다")
    void stableUnderInserts() {
        long p = product(1000, 10_000);
        String user = user();
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            original.add(create(user, p));
        }

        List<Long> seen = new ArrayList<>();
        Res page = get("/api/orders?userId=" + user + "&size=4");
        seen.addAll(ids(page.json().get("content")));
        while (!page.json().get("nextCursor").isNull()) {
            create(user, p); // 페이지 사이마다 새 주문이 들어온다
            create(user, p);
            page = get("/api/orders?userId=" + user + "&size=4&cursor=" + page.json().get("nextCursor").asText());
            seen.addAll(ids(page.json().get("content")));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
    }

    @Test
    @DisplayName("R9.5 다른 스레드가 계속 주문을 만드는 동안 순회해도 중복·누락이 없다")
    void stableUnderConcurrentInserts() throws Exception {
        long p = product(1000, 100_000);
        String user = user();
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            original.add(create(user, p));
        }
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                create(user, p);
            }
        });
        writer.start();
        try {
            List<Long> seen = walk("userId=" + user, 3);

            assertThat(seen).doesNotHaveDuplicates();
            assertThat(seen).containsAll(original);
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    @Test
    @DisplayName("R9.5 같은 시각에 생성된 주문이 많아도 순회 결과가 같다")
    void sameInstantManyOrders() {
        long p = product(1000, 1000);
        String user = user();
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            created.add(create(user, p));
        }
        jdbc.update("update orders set created_at = ? where user_id = ?", Timestamp.from(Instant.now()), user);

        List<Long> seen = walk("userId=" + user, 5);

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(created);
    }
}
