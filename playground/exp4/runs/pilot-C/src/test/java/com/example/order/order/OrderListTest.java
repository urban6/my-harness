package com.example.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

/** R9. 주문 목록(keyset 페이지네이션). 격리를 위해 테스트마다 고유한 userId 로 조회한다. */
class OrderListTest extends IntegrationTestBase {

    private long productId;

    private long createOrderFor(String user) {
        if (productId == 0) {
            productId = newProduct(1000, 1_000_000);
        }
        return newOrder(user, null, items(productId, 1)).get("id").asLong();
    }

    private List<Long> createOrdersFor(String user, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(createOrderFor(user));
        }
        return ids;
    }

    private ResponseEntity<JsonNode> list(String query) {
        return get("/api/orders" + (query.isEmpty() ? "" : "?" + query));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private static List<Long> reversed(List<Long> list) {
        List<Long> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** 첫 페이지부터 nextCursor 가 null 이 될 때까지 순회해 페이지별 id 목록을 돌려준다. */
    private List<List<Long>> traverse(String baseQuery, int size) {
        List<List<Long>> pages = new ArrayList<>();
        String cursor = null;
        do {
            String query = baseQuery + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<JsonNode> res = list(query);
            assertStatus(res, 200);
            pages.add(ids(res.getBody()));
            cursor = res.getBody().get("nextCursor").isNull() ? null : res.getBody().get("nextCursor").asText();
            assertThat(pages.size()).isLessThan(100);
        } while (cursor != null);
        return pages;
    }

    // ------------------------------------------------------------------ R9.1 형태

    @Test
    @DisplayName("R9.1 목록은 {content, nextCursor} 이고 content 원소는 주문 조회(R3.5)와 같은 형태, 마지막 페이지 nextCursor=null")
    void r9_1_listShape() {
        String user = uniqueUser();
        long id = createOrderFor(user);

        ResponseEntity<JsonNode> res = list("userId=" + user);

        assertStatus(res, 200);
        List<String> fields = new ArrayList<>();
        res.getBody().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("content", "nextCursor");
        assertThat(res.getBody().get("content")).hasSize(1);
        assertThat(res.getBody().get("content").get(0)).isEqualTo(order(id));
        assertThat(res.getBody().has("nextCursor")).isTrue();
        assertThat(res.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 필터에 맞는 주문이 없으면 빈 content + nextCursor=null")
    void r9_1_emptyResult() {
        ResponseEntity<JsonNode> res = list("userId=" + uniqueUser());

        assertStatus(res, 200);
        assertThat(res.getBody().get("content")).isEmpty();
        assertThat(res.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 필터 없이도 200 (content 는 배열)")
    void r9_1_noFilterAccepted() {
        createOrderFor(uniqueUser());

        ResponseEntity<JsonNode> res = list("");

        assertStatus(res, 200);
        assertThat(res.getBody().get("content").isArray()).isTrue();
        assertThat(res.getBody().get("content").size()).isBetween(1, 20);
    }

    // ------------------------------------------------------------------ R9.2 필터

    @Test
    @DisplayName("R9.2 userId 필터는 해당 사용자의 주문만 돌려준다")
    void r9_2_userIdFilter() {
        String u = uniqueUser();
        String v = uniqueUser();
        List<Long> mine = createOrdersFor(u, 3);
        createOrdersFor(v, 2);

        JsonNode page = list("userId=" + u).getBody();

        assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(mine);
        page.get("content").forEach(n -> assertThat(n.get("userId").asText()).isEqualTo(u));
    }

    @Test
    @DisplayName("R9.2 status 필터는 해당 상태의 주문만 돌려준다")
    void r9_2_statusFilter() {
        String u = uniqueUser();
        long pending = createOrderFor(u);
        long paid = createOrderFor(u);
        payOk(paid);
        long cancelled = createOrderFor(u);
        cancel(cancelled);

        JsonNode paidPage = list("status=PAID&size=100").getBody();

        assertThat(ids(paidPage)).doesNotContain(pending, cancelled);
        paidPage.get("content").forEach(n -> assertThat(n.get("status").asText()).isEqualTo("PAID"));
        assertThat(ids(list("status=CANCELLED&userId=" + u).getBody())).containsExactly(cancelled);
        assertThat(ids(list("status=PENDING_PAYMENT&userId=" + u).getBody())).containsExactly(pending);
    }

    @Test
    @DisplayName("R9.2 userId 와 status 를 함께 주면 AND 로 필터링한다")
    void r9_2_userIdAndStatusAreAnded() {
        String u = uniqueUser();
        String v = uniqueUser();
        long uPending = createOrderFor(u);
        long uPaid = createOrderFor(u);
        payOk(uPaid);
        long vPaid = createOrderFor(v);
        payOk(vPaid);

        JsonNode page = list("userId=" + u + "&status=PAID").getBody();

        assertThat(ids(page)).containsExactly(uPaid);
        assertThat(ids(list("userId=" + u + "&status=SHIPPED").getBody())).isEmpty();
        assertThat(ids(list("userId=" + v + "&status=PAID").getBody())).containsExactly(vPaid);
        assertThat(uPending).isNotEqualTo(uPaid);
    }

    @ParameterizedTest(name = "R9.2 status=\"{0}\" (정의되지 않은 값)은 400")
    @ValueSource(strings = {"BOGUS", "paid", "Paid", "PENDING", "NULL", ""})
    void r9_2_undefinedStatusRejected(String status) {
        assertProblem(list("status=" + status), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.2 정의된 8개 상태값은 모두 400 이 아니다")
    void r9_2_allDefinedStatusesAccepted() {
        for (String status : new String[] {"PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "PAYMENT_FAILED",
                "EXPIRED", "CANCELLED", "REFUNDED"}) {
            assertStatus(list("status=" + status + "&userId=" + uniqueUser()), 200);
        }
    }

    // ------------------------------------------------------------------ R9.3 size / cursor

    @Test
    @DisplayName("R9.3 size 기본값은 20 이다 (21건 중 첫 페이지 20건 + nextCursor, 다음 페이지 1건)")
    void r9_3_defaultSizeIs20() {
        String u = uniqueUser();
        createOrdersFor(u, 21);

        JsonNode first = list("userId=" + u).getBody();

        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        JsonNode second = list("userId=" + u + "&cursor=" + first.get("nextCursor").asText()).getBody();
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest(name = "R9.3 size={0} 은 400")
    @ValueSource(strings = {"0", "-1", "101", "1000", "abc", "1.5", "2147483648"})
    void r9_3_invalidSizeRejected(String size) {
        assertProblem(list("userId=" + uniqueUser() + "&size=" + size), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 size 1 과 100 은 허용된다")
    void r9_3_sizeBoundariesAccepted() {
        String u = uniqueUser();
        createOrdersFor(u, 3);

        assertThat(list("userId=" + u + "&size=1").getBody().get("content")).hasSize(1);
        assertThat(list("userId=" + u + "&size=100").getBody().get("content")).hasSize(3);
    }

    @ParameterizedTest(name = "R9.3 해석할 수 없는 cursor \"{0}\" 은 400")
    @ValueSource(strings = {"garbage", "!!!", "1234", "bm90LWEtY3Vyc29y", "%00", "=="})
    void r9_3_unparsableCursorRejected(String cursor) {
        assertProblem(list("userId=" + uniqueUser() + "&cursor=" + cursor), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 cursor 가 빈 값이거나 아주 길어도 500 이 아니라 400")
    void r9_3_emptyOrHugeCursorRejected() {
        String huge = "A".repeat(5000);

        assertProblem(list("cursor="), 400, "VALIDATION_ERROR");
        assertProblem(list("cursor=" + huge), 400, "VALIDATION_ERROR");
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("not|a|cursor".getBytes());
        assertProblem(list("cursor=" + b64), 400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ R9.4 정렬

    @Test
    @DisplayName("R9.4 createdAt 내림차순(같으면 id 내림차순)으로 정렬된다 — 나중에 만든 주문이 먼저")
    void r9_4_sortedByCreatedAtDescThenIdDesc() {
        String u = uniqueUser();
        List<Long> created = createOrdersFor(u, 6);

        JsonNode page = list("userId=" + u).getBody();

        assertThat(ids(page)).containsExactlyElementsOf(reversed(created));
        OffsetDateTime previousCreatedAt = null;
        long previousId = Long.MAX_VALUE;
        for (JsonNode n : page.get("content")) {
            OffsetDateTime createdAt = time(n, "createdAt");
            if (previousCreatedAt != null) {
                assertThat(createdAt).isBeforeOrEqualTo(previousCreatedAt);
                if (createdAt.isEqual(previousCreatedAt)) {
                    assertThat(n.get("id").asLong()).isLessThan(previousId);
                }
            }
            previousCreatedAt = createdAt;
            previousId = n.get("id").asLong();
        }
    }

    @Test
    @DisplayName("R9.4 createdAt 이 완전히 같은 주문은 id 내림차순이다 (DB 에서 createdAt 을 같게 맞춤)")
    void r9_4_ties_brokenByIdDesc() {
        String u = uniqueUser();
        List<Long> created = createOrdersFor(u, 5);
        jdbc.update("update orders set created_at = timestamptz '2031-05-05 05:05:05.123456+00', "
                + "expires_at = timestamptz '2031-05-05 05:20:05.123456+00' where user_id = ?", u);

        assertThat(ids(list("userId=" + u).getBody())).containsExactlyElementsOf(reversed(created));
        List<List<Long>> pages = traverse("userId=" + u, 2);
        assertThat(pages).hasSize(3);
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(reversed(created));
    }

    @Test
    @DisplayName("R9.4 createdAt 순서가 id 순서와 다른 주문도 createdAt 우선으로 정렬된다")
    void r9_4_createdAtTakesPrecedenceOverId() {
        String u = uniqueUser();
        List<Long> created = createOrdersFor(u, 3); // id 오름차순 a < b < c
        long a = created.get(0);
        long b = created.get(1);
        long c = created.get(2);
        // a 를 가장 최신, c 를 가장 오래된 것으로 뒤집는다.
        jdbc.update("update orders set created_at = timestamptz '2032-01-01 00:00:03+00', "
                + "expires_at = timestamptz '2032-01-01 00:15:03+00' where id = ?", a);
        jdbc.update("update orders set created_at = timestamptz '2032-01-01 00:00:02+00', "
                + "expires_at = timestamptz '2032-01-01 00:15:02+00' where id = ?", b);
        jdbc.update("update orders set created_at = timestamptz '2032-01-01 00:00:01+00', "
                + "expires_at = timestamptz '2032-01-01 00:15:01+00' where id = ?", c);

        assertThat(ids(list("userId=" + u).getBody())).containsExactly(a, b, c);
        assertThat(traverse("userId=" + u, 1)).containsExactly(List.of(a), List.of(b), List.of(c));
    }

    // ------------------------------------------------------------------ 페이지 순회

    @Test
    @DisplayName("R9.1/R9.4 size=3 으로 7건을 순회하면 3,3,1 로 나뉘고 중복·누락 없이 전체 정렬 순서를 따른다")
    void r9_1_traversalCoversEverythingOnce() {
        String u = uniqueUser();
        List<Long> created = createOrdersFor(u, 7);

        List<List<Long>> pages = traverse("userId=" + u, 3);

        assertThat(pages).extracting(List::size).containsExactly(3, 3, 1);
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(reversed(created));
    }

    @Test
    @DisplayName("R9.1 정확히 size 건이 남은 마지막 페이지의 nextCursor 는 null 이다 (4건, size=2 -> 2,2)")
    void r9_1_exactFitLastPageHasNullNextCursor() {
        String u = uniqueUser();
        createOrdersFor(u, 4);

        List<List<Long>> pages = traverse("userId=" + u, 2);

        assertThat(pages).extracting(List::size).containsExactly(2, 2);
    }

    @Test
    @DisplayName("R9.1 size 와 필터를 함께 쓰고 status 필터로 페이지를 순회해도 필터 밖 주문이 섞이지 않는다")
    void r9_1_traversalWithStatusFilter() {
        String u = uniqueUser();
        List<Long> paid = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = createOrderFor(u);
            if (i % 2 == 0) {
                payOk(id);
                paid.add(id);
            }
        }

        List<List<Long>> pages = traverse("userId=" + u + "&status=PAID", 2);

        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(reversed(paid));
    }

    // ------------------------------------------------------------------ R9.5 순회 중 신규 주문

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 첫 페이지 시점의 주문은 중복·누락 없이 정확히 한 번씩 나온다")
    void r9_5_newOrdersBetweenPagesDoNotDisturbTraversal() {
        String u = uniqueUser();
        List<Long> original = createOrdersFor(u, 7);
        List<Long> seen = new ArrayList<>();
        JsonNode page = list("userId=" + u + "&size=2").getBody();
        seen.addAll(ids(page));

        while (!page.get("nextCursor").isNull()) {
            createOrdersFor(u, 2); // 같은 사용자에게 새 주문
            createOrderFor(uniqueUser()); // 다른 사용자에게도
            page = list("userId=" + u + "&size=2&cursor=" + page.get("nextCursor").asText()).getBody();
            seen.addAll(ids(page));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
        // 첫 페이지 시점의 주문은 원래 순서대로 한 번씩 나온다.
        assertThat(seen.stream().filter(original::contains).toList()).containsExactlyElementsOf(reversed(original));
    }

    @Test
    @DisplayName("R9.5 순회 중 다른 스레드가 계속 주문을 만들어도 중복이 없다")
    void r9_5_concurrentCreationDuringTraversalCausesNoDuplicates() throws Exception {
        String u = uniqueUser();
        List<Long> original = createOrdersFor(u, 9);
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        Future<?> producing = producer.submit(() -> {
            while (!stop.get()) {
                createOrderFor(u);
            }
        });
        List<Long> seen = new ArrayList<>();
        try {
            JsonNode page = list("userId=" + u + "&size=2").getBody();
            seen.addAll(ids(page));
            // 첫 페이지 이후 생성된 주문이 확실히 존재하도록 생성기가 한 건 이상 만들 때까지 기다린다.
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, u)
                    <= original.size()) {
                assertThat(System.nanoTime()).isLessThan(waitUntil);
                Thread.sleep(10);
            }
            while (!page.get("nextCursor").isNull()) {
                page = list("userId=" + u + "&size=2&cursor=" + page.get("nextCursor").asText()).getBody();
                seen.addAll(ids(page));
            }
        } finally {
            stop.set(true);
            producing.get(30, TimeUnit.SECONDS);
            producer.shutdownNow();
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
        Set<Long> onlyOriginal = new HashSet<>(original);
        assertThat(seen.stream().filter(onlyOriginal::contains).toList())
                .containsExactlyElementsOf(reversed(original));
    }
}
