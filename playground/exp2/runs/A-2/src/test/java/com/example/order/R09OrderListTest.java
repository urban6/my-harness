package com.example.order;

import com.example.order.support.Api.Resp;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R9 주문 목록")
class R09OrderListTest extends IntegrationTest {

    private Resp list(String query) {
        return api.get("/api/orders" + (query.isEmpty() ? "" : "?" + query));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(o -> ids.add(o.path("id").asLong()));
        return ids;
    }

    /** 끝까지 넘기며 id를 모은다. */
    private List<Long> collectAll(String filter, int size) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        do {
            String query = filter + "size=" + size
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode page = list(query).json();
            all.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("R9.1·R9.2 userId·status 필터(AND), 원소는 주문 조회와 같은 형태")
    void filters() {
        long p = createProduct(1000, 100);
        long a1 = placeOrderOk("alice", null, p, 1);
        long a2 = paidOrder("alice", null, p, 1);
        long b1 = paidOrder("bob", null, p, 1);
        long b2 = placeOrderOk("bob", null, p, 1);

        JsonNode all = list("").json();
        assertThat(ids(all)).containsExactlyInAnyOrder(a1, a2, b1, b2);
        assertThat(all.get("nextCursor").isNull()).isTrue();
        assertThat(all.path("content").get(0)).isEqualTo(order(all.path("content").get(0).path("id").asLong()));

        assertThat(ids(list("userId=alice").json())).containsExactlyInAnyOrder(a1, a2);
        assertThat(ids(list("status=PAID").json())).containsExactlyInAnyOrder(a2, b1);
        assertThat(ids(list("userId=bob&status=PENDING_PAYMENT").json())).containsExactly(b2);
        assertThat(ids(list("userId=carol").json())).isEmpty();
    }

    @Test
    @DisplayName("R9.2·R9.3 잘못된 status·size·cursor는 400")
    void invalidQuery() {
        assertProblem(list("status=SHIPPING"), 400, "VALIDATION_ERROR");
        assertProblem(list("size=0"), 400, "VALIDATION_ERROR");
        assertProblem(list("size=101"), 400, "VALIDATION_ERROR");
        assertProblem(list("size=ten"), 400, "VALIDATION_ERROR");
        assertProblem(list("cursor=not-a-cursor"), 400, "VALIDATION_ERROR");
        assertProblem(list("cursor=%21%21%21"), 400, "VALIDATION_ERROR");
        assertThat(list("size=1").status()).isEqualTo(200);
        assertThat(list("size=100").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R9.3 size 기본값은 20")
    void defaultSize() {
        long p = createProduct(1000, 100);
        for (int i = 0; i < 21; i++) {
            placeOrderOk("u1", null, p, 1);
        }
        JsonNode page = list("").json();
        assertThat(page.path("content")).hasSize(20);
        assertThat(page.path("nextCursor").isTextual()).isTrue();
    }

    @Test
    @DisplayName("R9.4 createdAt 내림차순, 같으면 id 내림차순")
    void ordering() {
        long p = createProduct(1000, 100);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            created.add(placeOrderOk("u1", null, p, 1));
        }
        // 일부 주문의 createdAt을 같게 만들어 id 정렬을 확인한다
        jdbc.update("update orders set created_at = (select created_at from orders where id = ?) where id in (?, ?, ?)",
                created.get(3), created.get(1), created.get(2), created.get(3));

        JsonNode content = list("").json().path("content");
        List<JsonNode> orders = new ArrayList<>();
        content.forEach(orders::add);
        assertThat(orders).isSortedAccordingTo(Comparator
                .comparing((JsonNode o) -> OffsetDateTime.parse(o.path("createdAt").asText()).toInstant()).reversed()
                .thenComparing(o -> o.path("id").asLong(), Comparator.reverseOrder()));
        assertThat(ids(list("").json())).containsExactly(
                created.get(5), created.get(4), created.get(3), created.get(2), created.get(1), created.get(0));

        // 동점 구간을 가로지르는 페이지 넘김도 정확해야 한다
        assertThat(collectAll("", 2)).containsExactly(
                created.get(5), created.get(4), created.get(3), created.get(2), created.get(1), created.get(0));
    }

    @Test
    @DisplayName("R9.1 커서로 끝까지 넘기면 모든 주문이 한 번씩, 마지막 nextCursor는 null")
    void pagination() {
        long p = createProduct(1000, 100);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(placeOrderOk(i % 2 == 0 ? "even" : "odd", null, p, 1));
        }

        JsonNode first = list("size=10").json();
        assertThat(first.path("content")).hasSize(10);
        List<Long> all = collectAll("", 10);
        assertThat(all).hasSize(25).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        assertThat(collectAll("userId=even&", 4)).hasSize(13).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("R9.5 넘기는 사이 새 주문이 생겨도 첫 페이지 시점의 주문은 정확히 한 번씩")
    void stableUnderInserts() {
        long p = createProduct(1000, 100);
        List<Long> existing = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            existing.add(placeOrderOk("u1", null, p, 1));
        }

        List<Long> seen = new ArrayList<>();
        JsonNode page = list("size=5").json();
        seen.addAll(ids(page));
        while (!page.path("nextCursor").isNull()) {
            placeOrderOk("u1", null, p, 1);
            placeOrderOk("u2", null, p, 1);
            page = list("size=5&cursor=" + URLEncoder.encode(page.path("nextCursor").asText(), StandardCharsets.UTF_8)).json();
            seen.addAll(ids(page));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(existing);
        assertThat(seen.stream().filter(existing::contains).count()).isEqualTo(12);
    }
}
