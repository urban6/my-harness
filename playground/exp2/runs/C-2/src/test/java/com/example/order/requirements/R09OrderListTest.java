package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** R9. 주문 목록 */
class R09OrderListTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private List<Long> createOrders(String user, long productId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ApiResponse r = postOrder(user, uniqueKey(), orderJson(null, line(productId, 1)));
            assertThat(r.status()).as("create #%d: %s", i, r).isEqualTo(201);
            ids.add(r.id());
        }
        return ids;
    }

    private static List<Long> idsOf(ApiResponse page) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : page.json().get("content")) {
            ids.add(n.get("id").asLong());
        }
        return ids;
    }

    private static String nextCursor(ApiResponse page) {
        JsonNode n = page.json().get("nextCursor");
        return n == null || n.isNull() ? null : n.asText();
    }

    private ApiResponse page(String user, int size, String cursor) {
        return listOrders("userId=" + user + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
    }

    // ------------------------------------------------------------------ R9.1

    @Test
    @DisplayName("R9.1 목록은 200 {content[], nextCursor}이고 content 원소는 R3.5 조회 본문과 같다")
    void r9_1_shape_contentElementsEqualGetBody() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        List<Long> ids = createOrders(user, pid, 3);
        cancel(ids.get(1));

        ApiResponse r = listOrders("userId=" + user);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("content", "nextCursor");
        assertThat(r.json().get("content")).hasSize(3);
        for (JsonNode element : r.json().get("content")) {
            assertThat(element).isEqualTo(getOrder(element.get("id").asLong()).json());
        }
    }

    @Test
    @DisplayName("R9.1 다음 페이지가 없으면 nextCursor는 null이다 (필드는 존재)")
    void r9_1_lastPage_nextCursorIsNull() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, pid, 2);

        ApiResponse r = listOrders("userId=" + user + "&size=2");

        assertThat(r.json().has("nextCursor")).isTrue();
        assertThat(r.json().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 결과가 없으면 content는 빈 배열이고 nextCursor는 null이다")
    void r9_1_noResults_emptyContent() {
        ApiResponse r = listOrders("userId=" + uniqueUser());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("content").isArray()).isTrue();
        assertThat(r.json().get("content")).isEmpty();
        assertThat(r.json().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 마지막 페이지가 정확히 size개로 끝나면 nextCursor는 null이다 (size=3, 주문 6건)")
    void r9_1_exactMultipleOfSize_lastPageHasNullCursor() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, pid, 6);

        ApiResponse first = page(user, 3, null);
        ApiResponse second = page(user, 3, nextCursor(first));

        assertThat(nextCursor(first)).isNotNull();
        assertThat(idsOf(second)).hasSize(3);
        assertThat(nextCursor(second)).isNull();
    }

    // ------------------------------------------------------------------ R9.2

    @Test
    @DisplayName("R9.2 userId 필터는 그 사용자의 주문만 돌려준다")
    void r9_2_userIdFilter() {
        long pid = newProduct(1_000, 100);
        String alice = uniqueUser();
        String bob = uniqueUser();
        List<Long> aliceIds = createOrders(alice, pid, 3);
        createOrders(bob, pid, 2);

        ApiResponse r = listOrders("userId=" + alice);

        assertThat(idsOf(r)).containsExactlyInAnyOrderElementsOf(aliceIds);
        for (JsonNode n : r.json().get("content")) {
            assertThat(n.get("userId").asText()).isEqualTo(alice);
        }
    }

    @Test
    @DisplayName("R9.2 status 필터는 그 상태의 주문만 돌려준다")
    void r9_2_statusFilter() {
        newOrderInStatus("CANCELLED");

        ApiResponse r = listOrders("status=CANCELLED&size=100");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("content")).isNotEmpty();
        for (JsonNode n : r.json().get("content")) {
            assertThat(n.get("status").asText()).isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("R9.2 userId와 status를 함께 주면 AND로 걸린다")
    void r9_2_userIdAndStatus_areAnded() {
        long pid = newProduct(1_000, 100);
        String alice = uniqueUser();
        String bob = uniqueUser();
        List<Long> aliceIds = createOrders(alice, pid, 3);
        List<Long> bobIds = createOrders(bob, pid, 2);
        cancel(aliceIds.get(0));
        cancel(bobIds.get(0));
        pay(aliceIds.get(1));

        assertThat(idsOf(listOrders("userId=" + alice + "&status=CANCELLED"))).containsExactly(aliceIds.get(0));
        assertThat(idsOf(listOrders("userId=" + alice + "&status=PAID"))).containsExactly(aliceIds.get(1));
        assertThat(idsOf(listOrders("userId=" + alice + "&status=PENDING_PAYMENT"))).containsExactly(aliceIds.get(2));
        assertThat(idsOf(listOrders("userId=" + bob + "&status=PAID"))).isEmpty();
        assertThat(idsOf(listOrders("userId=" + alice))).hasSize(3);
    }

    @Test
    @DisplayName("R9.2 정의된 8개 상태 이름은 모두 필터로 허용된다")
    void r9_2_allDefinedStatuses_accepted() {
        for (String status : List.of("PENDING_PAYMENT", "PAID", "PAYMENT_FAILED", "EXPIRED", "CANCELLED", "SHIPPED",
                "DELIVERED", "REFUNDED")) {
            assertThat(listOrders("userId=" + uniqueUser() + "&status=" + status).status()).as(status).isEqualTo(200);
        }
    }

    @ParameterizedTest(name = "R9.2 status={0} -> 400")
    @ValueSource(strings = {"BOGUS", "PENDING", "SHIPPING", "123"})
    @DisplayName("R9.2 정의되지 않은 status 값은 400 VALIDATION_ERROR이다")
    void r9_2_undefinedStatus_returns400(String status) {
        assertProblem(listOrders("status=" + status), 400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ R9.3

    @Test
    @DisplayName("R9.3 size 기본값은 20이다 (주문 21건 -> 20건 + nextCursor, 다음 페이지 1건)")
    void r9_3_defaultSizeIs20() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, pid, 21);

        ApiResponse first = listOrders("userId=" + user);
        ApiResponse second = listOrders("userId=" + user + "&cursor=" + nextCursor(first));

        assertThat(idsOf(first)).hasSize(20);
        assertThat(nextCursor(first)).isNotNull();
        assertThat(idsOf(second)).hasSize(1);
        assertThat(nextCursor(second)).isNull();
    }

    @Test
    @DisplayName("R9.3 size 경계 1과 100은 허용된다")
    void r9_3_sizeBoundaries_accepted() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, pid, 3);

        assertThat(idsOf(page(user, 1, null))).hasSize(1);
        assertThat(idsOf(page(user, 100, null))).hasSize(3);
    }

    @ParameterizedTest(name = "R9.3 size={0} -> 400")
    @ValueSource(strings = {"0", "-1", "101", "1000", "abc", "1.5", "2147483648"})
    @DisplayName("R9.3 size가 1~100 밖이거나 정수가 아니면 400 VALIDATION_ERROR이다")
    void r9_3_invalidSize_returns400(String size) {
        assertProblem(listOrders("size=" + size), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "R9.3 cursor={0} -> 400")
    @ValueSource(strings = {"not-a-cursor!!", "YWJj", "MTIzNDU", "////"})
    @DisplayName("R9.3 해석할 수 없는 cursor는 400 VALIDATION_ERROR이다")
    void r9_3_invalidCursor_returns400(String cursor) {
        assertProblem(listOrders("cursor=" + cursor), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 응답으로 받은 nextCursor는 그대로 다음 요청에 쓸 수 있다 (200)")
    void r9_3_issuedCursor_isAccepted() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, pid, 3);
        String cursor = nextCursor(page(user, 1, null));

        assertThat(page(user, 1, cursor).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ R9.4

    @Test
    @DisplayName("R9.4 정렬은 createdAt 내림차순이다 (나중에 만든 주문이 먼저)")
    void r9_4_sortedByCreatedAtDesc() {
        long pid = newProduct(1_000, 100);
        String user = uniqueUser();
        List<Long> created = createOrders(user, pid, 10);

        ApiResponse r = listOrders("userId=" + user);

        List<Instant> createdAts = new ArrayList<>();
        for (JsonNode n : r.json().get("content")) {
            createdAts.add(OffsetDateTime.parse(n.get("createdAt").asText()).toInstant());
        }
        assertThat(createdAts).isSortedAccordingTo(Comparator.reverseOrder());
        List<Long> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected);
        assertThat(idsOf(r)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("R9.4 createdAt이 같으면 id 내림차순이고, 페이지 경계가 동률 한가운데여도 중복·누락이 없다")
    void r9_4_sameCreatedAt_tieBrokenByIdDesc_acrossPages() {
        String user = uniqueUser();
        OffsetDateTime sameInstant = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 123_456_000, ZoneOffset.UTC);
        OffsetDateTime expires = sameInstant.plusYears(1);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO orders(user_id, status, coupon_code, subtotal, discount, total_price, payment_id,
                                       created_at, expires_at, paid_at, updated_at)
                    VALUES (?, 'CANCELLED', NULL, 1000, 0, 1000, NULL, ?, ?, NULL, ?) RETURNING id
                    """, Long.class, user, sameInstant, expires, sameInstant));
        }
        List<Long> expected = new ArrayList<>(ids);
        expected.sort(Comparator.reverseOrder());

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            ApiResponse p = page(user, 3, cursor);
            assertThat(p.status()).isEqualTo(200);
            seen.addAll(idsOf(p));
            cursor = nextCursor(p);
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ R9.5

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생기고 기존 주문 상태가 바뀌어도 첫 페이지 시점의 주문은 중복·누락 없이 한 번씩 나온다")
    void r9_5_newOrdersAndStateChangesBetweenPages_noDuplicatesNoOmissions() {
        long pid = newProduct(1_000, 1_000);
        String user = uniqueUser();
        List<Long> original = createOrders(user, pid, 10);

        List<Long> seen = new ArrayList<>();
        ApiResponse first = page(user, 3, null);
        seen.addAll(idsOf(first));
        String cursor = nextCursor(first);
        createOrders(user, pid, 4); // 새 주문 4건
        cancel(original.get(0));    // 아직 나오지 않은 가장 오래된 주문의 상태 변경
        pay(original.get(5));       // 이미 나온/나올 주문의 상태 변경
        while (cursor != null) {
            ApiResponse p = page(user, 3, cursor);
            assertThat(p.status()).isEqualTo(200);
            seen.addAll(idsOf(p));
            cursor = nextCursor(p);
            createOrders(user, pid, 1); // 페이지마다 새 주문
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
        assertThat(seen.stream().filter(original::contains).count()).isEqualTo(original.size());
    }

    @Test
    @DisplayName("R9.5 다른 스레드가 계속 주문을 만드는 동안 25건을 size=4로 넘겨도 원래 주문은 정확히 한 번씩 나온다")
    void r9_5_concurrentCreationWhilePaging_noDuplicatesNoOmissions() {
        long pid = newProduct(1_000, 1_000_000);
        String user = uniqueUser();
        List<Long> original = createOrders(user, pid, 25);
        AtomicBoolean stop = new AtomicBoolean();
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            while (!stop.get()) {
                postOrder(user, uniqueKey(), orderJson(null, line(pid, 1)));
            }
        });

        List<Long> seen = new ArrayList<>();
        try {
            String cursor = null;
            do {
                ApiResponse p = page(user, 4, cursor);
                assertThat(p.status()).isEqualTo(200);
                seen.addAll(idsOf(p));
                cursor = nextCursor(p);
            } while (cursor != null);
        } finally {
            stop.set(true);
            writer.join();
        }

        Set<Long> unique = new HashSet<>(seen);
        assertThat(unique).as("중복 없음").hasSameSizeAs(seen);
        assertThat(unique).as("누락 없음").containsAll(original);
    }
}
