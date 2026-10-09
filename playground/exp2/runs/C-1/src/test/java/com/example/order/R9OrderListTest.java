package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R9 주문 목록")
class R9OrderListTest extends IntegrationTestBase {

    private long product(int stock) {
        return createProduct("상품", 1000, stock).get("id").asLong();
    }

    private ResponseEntity<JsonNode> list(String query) {
        return get("/api/orders" + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    private JsonNode listOk(String query) {
        ResponseEntity<JsonNode> res = list(query);
        assertThat(res.getStatusCode().value()).as("목록 응답: %s", res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private static String nextCursor(JsonNode page) {
        JsonNode c = page.get("nextCursor");
        return c == null || c.isNull() ? null : c.asText();
    }

    /** 끝까지 넘기며 id 를 모은다. */
    private List<Long> walk(String baseQuery, int maxPages) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < maxPages; i++) {
            String q = baseQuery + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = listOk(q);
            all.addAll(ids(page));
            cursor = nextCursor(page);
            if (cursor == null) {
                return all;
            }
        }
        throw new AssertionError("페이지가 끝나지 않는다: " + all);
    }

    private long insertOrder(String userId, String status, String createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO orders (user_id, status, subtotal, discount, total_price, created_at, expires_at) "
                        + "VALUES (?, ?, 0, 0, 0, ?::timestamptz, '2099-01-01T00:00:00Z'::timestamptz) RETURNING id",
                Long.class, userId, status, createdAt);
    }

    // ---------------- R9.1 ----------------

    @Test
    @DisplayName("R9.1 목록은 200 {content, nextCursor}, content 원소는 R3.5 형태, 마지막 페이지의 nextCursor 는 null")
    void r9_1_shape() {
        long p = product(10);
        long a = orderOk("u1", null, p, 1).get("id").asLong();
        long b = orderOk("u2", null, p, 2).get("id").asLong();

        JsonNode page = listOk(null);

        assertThat(page.fieldNames()).toIterable().containsExactlyInAnyOrder("content", "nextCursor");
        assertThat(page.has("nextCursor")).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(ids(page)).containsExactly(b, a);
        JsonNode first = page.get("content").get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt");
        assertThat(first).isEqualTo(getOrder(b));
    }

    @Test
    @DisplayName("R9.1 주문이 없으면 content=[] 이고 nextCursor=null")
    void r9_1_empty() {
        JsonNode page = listOk(null);

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 정확히 size 건만 있으면 nextCursor 는 null (다음 페이지가 없다)")
    void r9_1_exactlyOnePageHasNoCursor() {
        long p = product(10);
        orderOk("u1", null, p, 1);
        orderOk("u1", null, p, 1);

        JsonNode page = listOk("size=2");

        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 size 보다 많으면 nextCursor 가 있고, 마지막 페이지에서 null")
    void r9_1_cursorChain() {
        long p = product(20);
        for (int i = 0; i < 5; i++) {
            orderOk("u1", null, p, 1);
        }

        JsonNode p1 = listOk("size=2");
        JsonNode p2 = listOk("size=2&cursor=" + nextCursor(p1));
        JsonNode p3 = listOk("size=2&cursor=" + nextCursor(p2));

        assertThat(p1.get("content")).hasSize(2);
        assertThat(nextCursor(p1)).isNotBlank();
        assertThat(p2.get("content")).hasSize(2);
        assertThat(nextCursor(p2)).isNotBlank();
        assertThat(p3.get("content")).hasSize(1);
        assertThat(nextCursor(p3)).isNull();
    }

    // ---------------- R9.2 ----------------

    @Test
    @DisplayName("R9.2 userId 필터")
    void r9_2_userIdFilter() {
        long p = product(20);
        long a1 = orderOk("alice", null, p, 1).get("id").asLong();
        orderOk("bob", null, p, 1);
        long a2 = orderOk("alice", null, p, 1).get("id").asLong();

        assertThat(ids(listOk("userId=alice"))).containsExactly(a2, a1);
        assertThat(listOk("userId=nobody").get("content")).isEmpty();
    }

    @Test
    @DisplayName("R9.2 status 필터")
    void r9_2_statusFilter() {
        long p = product(20);
        long pending = orderOk("u1", null, p, 1).get("id").asLong();
        long cancelled = orderOk("u1", null, p, 1).get("id").asLong();
        long paid = orderOk("u2", null, p, 1).get("id").asLong();
        cancel(cancelled);
        payOk(paid);

        assertThat(ids(listOk("status=PENDING_PAYMENT"))).containsExactly(pending);
        assertThat(ids(listOk("status=CANCELLED"))).containsExactly(cancelled);
        assertThat(ids(listOk("status=PAID"))).containsExactly(paid);
        assertThat(listOk("status=DELIVERED").get("content")).isEmpty();
    }

    @Test
    @DisplayName("R9.2 userId 와 status 를 함께 주면 AND")
    void r9_2_bothFiltersAreAnd() {
        long p = product(20);
        long aliceCancelled = orderOk("alice", null, p, 1).get("id").asLong();
        long alicePending = orderOk("alice", null, p, 1).get("id").asLong();
        long bobCancelled = orderOk("bob", null, p, 1).get("id").asLong();
        cancel(aliceCancelled);
        cancel(bobCancelled);

        assertThat(ids(listOk("userId=alice&status=CANCELLED"))).containsExactly(aliceCancelled);
        assertThat(ids(listOk("userId=alice&status=PENDING_PAYMENT"))).containsExactly(alicePending);
        assertThat(ids(listOk("userId=bob&status=PENDING_PAYMENT"))).isEmpty();
    }

    @Test
    @DisplayName("R9.2 필터와 커서를 함께 쓰면 필터에 맞는 주문만 중복·누락 없이 나온다")
    void r9_2_filterWithPagination() {
        long p = product(40);
        List<Long> alice = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            alice.add(0, orderOk("alice", null, p, 1).get("id").asLong());
            orderOk("bob", null, p, 1);
        }

        assertThat(walk("userId=alice&size=2", 10)).containsExactlyElementsOf(alice);
    }

    @ParameterizedTest(name = "R9.2 status={0} -> 400")
    @ValueSource(strings = {"FOO", "paid", "PENDING", "1"})
    void r9_2_unknownStatus(String status) {
        assertProblem(list("status=" + status), 400, "VALIDATION_ERROR");
    }

    // ---------------- R9.3 ----------------

    @Test
    @DisplayName("R9.3 size 기본값은 20")
    void r9_3_defaultSize() {
        long p = product(100);
        for (int i = 0; i < 21; i++) {
            orderOk("u1", null, p, 1);
        }

        JsonNode page = listOk(null);

        assertThat(page.get("content")).hasSize(20);
        assertThat(nextCursor(page)).isNotNull();
        assertThat(listOk("cursor=" + nextCursor(page)).get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R9.3 size 1 과 100 은 허용")
    void r9_3_sizeBoundariesAccepted() {
        long p = product(10);
        orderOk("u1", null, p, 1);
        orderOk("u1", null, p, 1);

        assertThat(listOk("size=1").get("content")).hasSize(1);
        assertThat(nextCursor(listOk("size=1"))).isNotNull();
        assertThat(listOk("size=100").get("content")).hasSize(2);
    }

    @ParameterizedTest(name = "R9.3 size={0} -> 400")
    @ValueSource(strings = {"0", "-1", "101", "1000", "abc", "1.5"})
    void r9_3_invalidSize(String size) {
        assertProblem(list("size=" + size), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R9.3 cursor={0} -> 400")
    @ValueSource(strings = {"garbage!!", "abc", "bm90LWEtY3Vyc29y", "MTIzNDU2Nzg5"})
    void r9_3_invalidCursor(String cursor) {
        long p = product(10);
        orderOk("u1", null, p, 1);

        assertProblem(list("cursor=" + cursor), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 base64 로는 해석되지만 (createdAt, id) 형태가 아닌 cursor -> 400")
    void r9_3_wellFormedBase64ButMeaningless() {
        String c = Base64.getUrlEncoder().withoutPadding().encodeToString("hello|world".getBytes());

        assertProblem(list("cursor=" + c), 400, "VALIDATION_ERROR");
    }

    // ---------------- R9.4 ----------------

    @Test
    @DisplayName("R9.4 createdAt 내림차순(최신이 먼저)")
    void r9_4_createdAtDescending() {
        long p = product(20);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            created.add(0, orderOk("u" + (i % 2), null, p, 1).get("id").asLong());
        }

        JsonNode page = listOk(null);

        assertThat(ids(page)).containsExactlyElementsOf(created);
        List<OffsetDateTime> times = new ArrayList<>();
        page.get("content").forEach(n -> times.add(OffsetDateTime.parse(n.get("createdAt").asText())));
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i - 1).toInstant()).isAfterOrEqualTo(times.get(i).toInstant());
        }
    }

    @Test
    @DisplayName("R9.4 createdAt 이 같으면 id 내림차순, 페이지 경계가 동률 한가운데여도 중복·누락 없음")
    void r9_4_tieBreakById() {
        String same = "2026-01-01T00:00:00.123456Z";
        List<Long> sameIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sameIds.add(0, insertOrder("tie", "PENDING_PAYMENT", same));
        }
        long older = insertOrder("tie", "PENDING_PAYMENT", "2025-12-31T23:59:59.999999Z");
        long newer = insertOrder("tie", "PENDING_PAYMENT", "2026-01-01T00:00:00.123457Z");

        List<Long> expected = new ArrayList<>();
        expected.add(newer);
        expected.addAll(sameIds);
        expected.add(older);

        assertThat(ids(listOk(null))).containsExactlyElementsOf(expected);
        assertThat(walk("size=2", 10)).containsExactlyElementsOf(expected);
        assertThat(walk("size=1", 10)).containsExactlyElementsOf(expected);
        assertThat(walk("size=3", 10)).containsExactlyElementsOf(expected);
    }

    // ---------------- R9.5 ----------------

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생기고 상태가 바뀌어도, 첫 페이지 시점의 주문은 정확히 한 번씩 나온다")
    void r9_5_newOrdersBetweenPages() {
        long p = product(100);
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            original.add(0, orderOk("u1", null, p, 1).get("id").asLong());
        }

        JsonNode page1 = listOk("size=3");
        List<Long> seen = new ArrayList<>(ids(page1));
        for (int i = 0; i < 3; i++) {
            orderOk("u2", null, p, 1);
        }
        cancel(original.get(4));
        String cursor = nextCursor(page1);
        while (cursor != null) {
            JsonNode page = listOk("size=3&cursor=" + cursor);
            seen.addAll(ids(page));
            // 중간에 또 새 주문을 끼워 넣는다
            orderOk("u3", null, p, 1);
            cursor = nextCursor(page);
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
        assertThat(seen.stream().filter(original::contains).toList()).containsExactlyElementsOf(original);
    }

    @Test
    @DisplayName("R9.5 다른 스레드가 계속 주문을 만드는 동안 페이지를 끝까지 넘겨도 기존 주문 30건은 중복·누락이 없다")
    void r9_5_concurrentInsertsWhilePaging() throws Exception {
        long p = product(1000);
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            original.add(0, orderOk("seed", null, p, 1).get("id").asLong());
        }
        AtomicBoolean stop = new AtomicBoolean();
        Thread inserter = new Thread(() -> {
            int n = 0;
            while (!stop.get() && n < 150) {
                createOrder("writer", key(), orderBody(null, p, 1));
                n++;
            }
        });
        inserter.start();
        try {
            List<Long> seen = new ArrayList<>();
            String cursor = null;
            int guard = 0;
            do {
                JsonNode page = listOk("size=4" + (cursor == null ? "" : "&cursor=" + cursor));
                seen.addAll(ids(page));
                cursor = nextCursor(page);
                Thread.sleep(30);
                assertThat(++guard).isLessThan(200);
            } while (cursor != null);

            assertThat(seen).doesNotHaveDuplicates();
            Set<Long> seenSet = new HashSet<>(seen);
            assertThat(seenSet).containsAll(original);
            assertThat(seen.stream().filter(original::contains).toList()).containsExactlyElementsOf(original);
        } finally {
            stop.set(true);
            inserter.join(60_000);
        }
    }
}
