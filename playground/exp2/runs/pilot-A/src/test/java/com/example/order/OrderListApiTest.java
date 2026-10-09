package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.Api.Response;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R9 주문 목록")
class OrderListApiTest extends IntegrationTest {

    private List<Long> createOrders(String user, int count) {
        long p = createProduct(100, 1000);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createOrder(user, null, p, 1).get("id").asLong());
        }
        return ids;
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }

    /** 모든 페이지를 따라가며 id를 모은다. */
    private List<Long> collectAll(String query) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        do {
            Response r = api.get("/api/orders?" + query + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            all.addAll(ids(r.body()));
            cursor = r.body().get("nextCursor").isNull() ? null : r.body().get("nextCursor").asText();
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("R9.1/R9.4 userId 필터, createdAt·id 내림차순, 원소는 주문 조회와 같은 형태")
    void listByUser() {
        String user = uniqueUser();
        List<Long> created = createOrders(user, 4);

        Response r = api.get("/api/orders?userId=" + user);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("nextCursor").isNull()).isTrue();
        assertThat(ids(r.body())).containsExactlyElementsOf(created.reversed());
        assertThat(r.body().get("content").get(0)).isEqualTo(order(created.getLast()));

        List<JsonNode> content = new ArrayList<>();
        r.body().get("content").forEach(content::add);
        Comparator<JsonNode> expected = Comparator
                .comparing((JsonNode o) -> OffsetDateTime.parse(o.get("createdAt").asText())).reversed()
                .thenComparing(o -> o.get("id").asLong(), Comparator.reverseOrder());
        assertThat(content).isSortedAccordingTo(expected);
    }

    @Test
    @DisplayName("R9.1/R9.3 size와 cursor로 페이지를 넘기고, 마지막 페이지의 nextCursor는 null")
    void pagination() {
        String user = uniqueUser();
        List<Long> created = createOrders(user, 5);

        Response first = api.get("/api/orders?userId=" + user + "&size=2");
        assertThat(ids(first.body())).containsExactly(created.get(4), created.get(3));
        String cursor = first.body().get("nextCursor").asText();

        Response second = api.get("/api/orders?userId=" + user + "&size=2&cursor=" + cursor);
        assertThat(ids(second.body())).containsExactly(created.get(2), created.get(1));

        Response third = api.get("/api/orders?userId=" + user + "&size=2&cursor="
                + second.body().get("nextCursor").asText());
        assertThat(ids(third.body())).containsExactly(created.get(0));
        assertThat(third.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.3 size 기본값은 20")
    void defaultSize() {
        String user = uniqueUser();
        createOrders(user, 21);
        Response r = api.get("/api/orders?userId=" + user);
        assertThat(r.body().get("content")).hasSize(20);
        assertThat(r.body().get("nextCursor").isNull()).isFalse();
        assertThat(collectAll("userId=" + user + "&size=100")).hasSize(21);
    }

    @Test
    @DisplayName("R9.2 userId·status 둘 다 주면 AND, status만 주면 그 상태만")
    void statusFilter() {
        String user = uniqueUser();
        long p = createProduct(100, 100);
        long paid = paidOrder(user, null, "tok", p, 1).get("id").asLong();
        long pending = createOrder(user, null, p, 1).get("id").asLong();
        long otherUserPaid = paidOrder(uniqueUser(), null, "tok", p, 1).get("id").asLong();

        assertThat(collectAll("userId=" + user + "&status=PAID")).containsExactly(paid);
        assertThat(collectAll("userId=" + user + "&status=PENDING_PAYMENT")).containsExactly(pending);
        assertThat(collectAll("userId=" + user + "&status=REFUNDED")).isEmpty();

        Response onlyStatus = api.get("/api/orders?status=PAID&size=100");
        onlyStatus.body().get("content")
                .forEach(o -> assertThat(o.get("status").asText()).isEqualTo("PAID"));
        assertThat(collectAll("status=PAID&size=100")).contains(paid, otherUserPaid).doesNotContain(pending);
    }

    @Test
    @DisplayName("R9.2/R9.3 정의되지 않은 status, 범위 밖 size, 해석할 수 없는 cursor는 400")
    void invalidQuery() {
        for (String query : List.of("status=UNKNOWN", "status=paid", "size=0", "size=101", "size=abc",
                "size=", "cursor=not-a-cursor!", "cursor=Zm9v", "cursor=")) {
            assertProblem(api.get("/api/orders?" + query), 400, "VALIDATION_ERROR");
        }
        assertThat(api.get("/api/orders?size=1").status()).isEqualTo(200);
        assertThat(api.get("/api/orders?size=100").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 기존 주문은 정확히 한 번씩 나온다")
    void stableUnderInserts() {
        String user = uniqueUser();
        List<Long> existing = createOrders(user, 7);

        List<Long> seen = new ArrayList<>();
        Response page = api.get("/api/orders?userId=" + user + "&size=3");
        seen.addAll(ids(page.body()));
        long p = createProduct(100, 100);
        while (!page.body().get("nextCursor").isNull()) {
            createOrder(user, null, p, 1); // 페이지 사이에 새 주문
            page = api.get("/api/orders?userId=" + user + "&size=3&cursor="
                    + page.body().get("nextCursor").asText());
            seen.addAll(ids(page.body()));
        }

        assertThat(seen).containsExactlyElementsOf(existing.reversed());
    }
}
