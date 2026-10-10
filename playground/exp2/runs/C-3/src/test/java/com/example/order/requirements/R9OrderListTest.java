package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R9. 주문 목록")
class R9OrderListTest extends IntegrationTestBase {

    /** 한 사용자의 주문 n 건을 만들고 id 를 생성 순서대로 돌려준다. */
    private List<Long> seedOrders(String user, int n) {
        long productId = product(1_000, n + 5);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(orderIdOk(user, orderBody(null, item(productId, 1))));
        }
        return ids;
    }

    private JsonNode list(String query) {
        ResponseEntity<String> r = get("/api/orders?" + query);
        assertThat(statusOf(r)).as("목록 응답 %s", r.getBody()).isEqualTo(200);
        return json(r);
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    /** nextCursor 가 null 이 될 때까지 따라가며 모든 id 를 모은다. */
    private List<Long> collectAll(String baseQuery, int size) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            JsonNode page = list(baseQuery + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
            all.addAll(idsOf(page));
            if (page.get("nextCursor").isNull()) {
                return all;
            }
            cursor = page.get("nextCursor").asText();
        }
        throw new IllegalStateException("커서가 끝나지 않음");
    }

    private long insertOrderRow(String user, String status, Instant createdAt) {
        OffsetDateTime created = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        OffsetDateTime expires = created.plusDays(2);
        return jdbc.queryForObject("""
                insert into orders (user_id, status, coupon_code, subtotal, discount, total_price, created_at, expires_at)
                values (?, ?, null, 0, 0, 0, ?, ?) returning id""", Long.class, user, status, created, expires);
    }

    // ---------------------------------------------------------------- R9.1

    @Test
    @DisplayName("R9.1 목록 응답은 {content[], nextCursor} 이고 content 원소는 R3.5 형태이다")
    void r9_1_response_hasContentAndNextCursor_withOrderShape() {
        String user = uid("u");
        seedOrders(user, 2);

        JsonNode page = list("userId=" + user);

        assertThat(page.fieldNames()).toIterable().containsExactlyInAnyOrder("content", "nextCursor");
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("content").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId",
                "status", "items", "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt", "paidAt");
    }

    @Test
    @DisplayName("R9.1 다음 페이지가 없으면 nextCursor 는 null 이다")
    void r9_1_lastPage_hasNullNextCursor() {
        String user = uid("u");
        seedOrders(user, 3);

        JsonNode page = list("userId=" + user + "&size=10");

        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 정확히 size 건만 남아 있으면 nextCursor 는 null 이다 (빈 다음 페이지를 약속하지 않는다)")
    void r9_1_exactlySizeRemaining_hasNullNextCursor() {
        String user = uid("u");
        seedOrders(user, 3);

        JsonNode page = list("userId=" + user + "&size=3");

        assertThat(page.get("content")).hasSize(3);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 다음 페이지가 있으면 nextCursor 가 문자열로 온다")
    void r9_1_morePages_hasNextCursorString() {
        String user = uid("u");
        seedOrders(user, 3);

        JsonNode page = list("userId=" + user + "&size=2");

        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("nextCursor").isTextual()).isTrue();
    }

    @Test
    @DisplayName("R9.1 일치하는 주문이 없으면 content 는 빈 배열이고 nextCursor 는 null")
    void r9_1_noMatches_returnsEmptyPage() {
        JsonNode page = list("userId=" + uid("nobody"));

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- R9.2

    @Test
    @DisplayName("R9.2 userId 필터는 그 사용자의 주문만 돌려준다")
    void r9_2_userIdFilter_returnsOnlyThatUser() {
        String alice = uid("alice");
        String bob = uid("bob");
        List<Long> aliceIds = seedOrders(alice, 2);
        seedOrders(bob, 3);

        JsonNode page = list("userId=" + alice);

        assertThat(idsOf(page)).containsExactlyInAnyOrderElementsOf(aliceIds);
        page.get("content").forEach(o -> assertThat(o.get("userId").asText()).isEqualTo(alice));
    }

    @Test
    @DisplayName("R9.2 status 필터는 그 상태의 주문만 돌려준다")
    void r9_2_statusFilter_returnsOnlyThatStatus() {
        String user = uid("u");
        List<Long> ids = seedOrders(user, 3);
        payOk(ids.get(0));
        cancel(ids.get(1));

        JsonNode paid = list("userId=" + user + "&status=PAID");
        JsonNode pending = list("userId=" + user + "&status=PENDING_PAYMENT");
        JsonNode cancelled = list("userId=" + user + "&status=CANCELLED");

        assertThat(idsOf(paid)).containsExactly(ids.get(0));
        assertThat(idsOf(pending)).containsExactly(ids.get(2));
        assertThat(idsOf(cancelled)).containsExactly(ids.get(1));
    }

    @Test
    @DisplayName("R9.2 userId 와 status 를 함께 주면 AND 로 걸러진다")
    void r9_2_userIdAndStatus_isConjunction() {
        String alice = uid("alice");
        String bob = uid("bob");
        List<Long> aliceIds = seedOrders(alice, 2);
        List<Long> bobIds = seedOrders(bob, 2);
        payOk(aliceIds.get(0));
        payOk(bobIds.get(0));

        JsonNode page = list("userId=" + alice + "&status=PAID");

        assertThat(idsOf(page)).containsExactly(aliceIds.get(0));
    }

    @Test
    @DisplayName("R9.2 필터를 모두 생략해도 200 이다 (전체 조회)")
    void r9_2_noFilters_returns200() {
        seedOrders(uid("u"), 1);

        JsonNode page = list("size=5");

        assertThat(page.get("content")).isNotEmpty();
        assertThat(page.get("content").size()).isLessThanOrEqualTo(5);
    }

    @ParameterizedTest(name = "[{index}] status={0}")
    @ValueSource(strings = {"UNKNOWN", "paid", "Paid", "PENDING", "123", "PAID,SHIPPED"})
    @DisplayName("R9.2 정의되지 않은 status 값이면 400")
    void r9_2_undefinedStatus_returns400(String status) {
        ResponseEntity<String> r = get("/api/orders?status=" + status);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest(name = "[{index}] status={0}")
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "PAYMENT_FAILED", "EXPIRED", "CANCELLED", "REFUNDED"})
    @DisplayName("R9.2 정의된 8개 status 는 모두 허용된다")
    void r9_2_definedStatuses_return200(String status) {
        ResponseEntity<String> r = get("/api/orders?userId=" + uid("nobody") + "&status=" + status);

        assertThat(statusOf(r)).isEqualTo(200);
    }

    // ---------------------------------------------------------------- R9.3

    @Test
    @DisplayName("R9.3 size 를 생략하면 기본 20 건이 오고 다음 커서가 있다")
    void r9_3_defaultSize_is20() {
        String user = uid("u");
        seedOrders(user, 25);

        JsonNode page = list("userId=" + user);

        assertThat(page.get("content")).hasSize(20);
        assertThat(page.get("nextCursor").isNull()).isFalse();
        assertThat(idsOf(list("userId=" + user + "&cursor=" + page.get("nextCursor").asText()))).hasSize(5);
    }

    @ParameterizedTest(name = "[{index}] size={0}")
    @ValueSource(strings = {"0", "-1", "101", "1000", "abc", "1.5"})
    @DisplayName("R9.3 size 가 1 ~ 100 밖이거나 숫자가 아니면 400")
    void r9_3_invalidSize_returns400(String size) {
        ResponseEntity<String> r = get("/api/orders?size=" + size);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 size=1 이면 한 건씩 돌려준다")
    void r9_3_size1_returnsOneItem() {
        String user = uid("u");
        seedOrders(user, 2);

        JsonNode page = list("userId=" + user + "&size=1");

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    @DisplayName("R9.3 size=100 은 허용되고 최대 100 건을 돌려준다")
    void r9_3_size100_isAllowed() {
        String user = uid("u");
        seedOrders(user, 3);

        ResponseEntity<String> r = get("/api/orders?userId=" + user + "&size=100");

        assertThat(statusOf(r)).isEqualTo(200);
    }

    @ParameterizedTest(name = "[{index}] cursor={0}")
    @ValueSource(strings = {"!!!", "abc", "not-a-cursor", "a", "%20", "AAAA", "MTIzNA"})
    @DisplayName("R9.3 해석할 수 없는 cursor 이면 400")
    void r9_3_garbageCursor_returns400(String cursor) {
        ResponseEntity<String> r = get("/api/orders?cursor=" + cursor);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 base64 로는 유효하지만 형식이 틀린 cursor 이면 400")
    void r9_3_wellFormedBase64ButWrongContent_returns400() {
        String notACursor = Base64.getUrlEncoder().withoutPadding().encodeToString("hello:world".getBytes(StandardCharsets.UTF_8));
        String oneField = Base64.getUrlEncoder().withoutPadding().encodeToString("1234567".getBytes(StandardCharsets.UTF_8));
        String threeFields = Base64.getUrlEncoder().withoutPadding().encodeToString("1:2:3".getBytes(StandardCharsets.UTF_8));

        assertProblem(get("/api/orders?cursor=" + notACursor), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?cursor=" + oneField), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/orders?cursor=" + threeFields), 400, "VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- R9.4

    @Test
    @DisplayName("R9.4 createdAt 내림차순(같으면 id 내림차순)으로 정렬된다")
    void r9_4_sortedByCreatedAtDescThenIdDesc() {
        String user = uid("u");
        List<Long> ids = seedOrders(user, 8);

        JsonNode page = list("userId=" + user + "&size=100");

        List<Long> actual = idsOf(page);
        assertThat(actual).hasSize(8);
        for (int i = 0; i + 1 < actual.size(); i++) {
            JsonNode a = page.get("content").get(i);
            JsonNode b = page.get("content").get(i + 1);
            Instant ta = Instant.parse(a.get("createdAt").asText());
            Instant tb = Instant.parse(b.get("createdAt").asText());
            assertThat(ta).isAfterOrEqualTo(tb);
            if (ta.equals(tb)) {
                assertThat(a.get("id").asLong()).isGreaterThan(b.get("id").asLong());
            }
        }
        // 순차 생성이므로 생성 순서의 역순이어야 한다
        assertThat(actual).isEqualTo(ids.reversed());
    }

    @Test
    @DisplayName("R9.4 createdAt 이 같은 주문들은 id 내림차순으로 정렬된다")
    void r9_4_sameCreatedAt_tieBrokenByIdDesc() {
        String user = uid("u");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserted.add(insertOrderRow(user, "DELIVERED", t));
        }

        List<Long> actual = idsOf(list("userId=" + user + "&size=100"));

        assertThat(actual).isEqualTo(inserted.reversed());
    }

    @Test
    @DisplayName("R9.4/R9.5 createdAt 이 같은 주문이 페이지 경계에 걸쳐도 중복·누락 없이 id 내림차순으로 이어진다")
    void r9_5_tiesAcrossPageBoundary_noDuplicatesOrGaps() {
        String user = uid("u");
        Instant older = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        Instant tie = Instant.now().minusSeconds(1800).truncatedTo(ChronoUnit.MICROS);
        Instant newer = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        List<Long> olderIds = List.of(insertOrderRow(user, "DELIVERED", older), insertOrderRow(user, "DELIVERED", older));
        List<Long> tieIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tieIds.add(insertOrderRow(user, "DELIVERED", tie));
        }
        List<Long> newerIds = List.of(insertOrderRow(user, "DELIVERED", newer), insertOrderRow(user, "DELIVERED", newer));
        List<Long> expected = new ArrayList<>();
        expected.addAll(newerIds.reversed());
        expected.addAll(tieIds.reversed());
        expected.addAll(olderIds.reversed());

        List<Long> actual = collectAll("userId=" + user, 3);

        assertThat(actual).isEqualTo(expected);
    }

    // ---------------------------------------------------------------- R9.5

    @Test
    @DisplayName("R9.5 모든 페이지를 이으면 전체 주문이 중복·누락 없이 정확히 한 번씩 나온다")
    void r9_5_allPages_coverEveryOrderExactlyOnce() {
        String user = uid("u");
        List<Long> ids = seedOrders(user, 23);

        List<Long> actual = collectAll("userId=" + user, 5);

        assertThat(actual).hasSize(23).doesNotHaveDuplicates();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 첫 페이지 시점의 주문은 정확히 한 번씩 나온다")
    void r9_5_newOrdersDuringPaging_doNotCauseDuplicatesOrGaps() {
        String user = uid("u");
        List<Long> original = seedOrders(user, 12);
        long productId = product(1_000, 50);

        List<Long> seen = new ArrayList<>();
        JsonNode first = list("userId=" + user + "&size=5");
        seen.addAll(idsOf(first));
        String cursor = first.get("nextCursor").asText();
        for (int round = 0; ; round++) {
            // 페이지 사이마다 같은 사용자의 새 주문을 끼워 넣는다
            orderOk(user, orderBody(null, item(productId, 1)));
            orderOk(user, orderBody(null, item(productId, 1)));
            JsonNode page = list("userId=" + user + "&size=5&cursor=" + cursor);
            seen.addAll(idsOf(page));
            if (page.get("nextCursor").isNull()) {
                break;
            }
            cursor = page.get("nextCursor").asText();
            assertThat(round).isLessThan(20);
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original);
        assertThat(seen.stream().filter(original::contains).count()).isEqualTo(12L);
    }

    @Test
    @DisplayName("R9.5 다른 사용자의 새 주문이 끼어들어도 필터 없는 목록 순회에서 기존 주문이 중복되지 않는다")
    void r9_5_unfilteredPaging_withConcurrentInserts_hasNoDuplicates() {
        String user = uid("u");
        seedOrders(user, 6);
        long productId = product(1_000, 50);
        List<Long> seen = new ArrayList<>();
        JsonNode page = list("size=4");
        seen.addAll(idsOf(page));

        for (int i = 0; i < 6 && !page.get("nextCursor").isNull(); i++) {
            orderOk(uid("other"), orderBody(null, item(productId, 1)));
            page = list("size=4&cursor=" + page.get("nextCursor").asText());
            seen.addAll(idsOf(page));
        }

        Set<Long> unique = new HashSet<>(seen);
        assertThat(unique).hasSameSizeAs(seen);
    }

    @Test
    @DisplayName("R9.5 페이지 사이에 주문 상태가 바뀌어도(결제) 같은 주문이 두 번 나오지 않는다")
    void r9_5_statusChangesDuringPaging_doNotDuplicate() {
        String user = uid("u");
        List<Long> ids = seedOrders(user, 9);
        JsonNode first = list("userId=" + user + "&size=3");
        List<Long> seen = new ArrayList<>(idsOf(first));
        payOk(ids.get(0));
        cancel(ids.get(4));

        String cursor = first.get("nextCursor").asText();
        while (cursor != null) {
            JsonNode page = list("userId=" + user + "&size=3&cursor=" + cursor);
            seen.addAll(idsOf(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(9).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }
}
