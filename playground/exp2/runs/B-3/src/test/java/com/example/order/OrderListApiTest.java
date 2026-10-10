package com.example.order;

import static com.example.order.support.TestApi.newUserId;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R9 주문 목록")
class OrderListApiTest extends IntegrationTest {

    @Test
    @DisplayName("R9.1·R9.4 content는 주문 형태, createdAt 내림차순·id 내림차순, 마지막 페이지 nextCursor null")
    void listsNewestFirst() {
        long productId = api.createProduct(1_000, 100);
        String user = newUserId();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(api.createOrderId(user, productId, 1));
        }

        JsonNode page = api.get("/api/orders?userId=" + user).assertStatus(200).body();

        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("content")).hasSize(3);
        assertThat(ids(page)).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        assertThat(page.get("content").get(0)).isEqualTo(api.order(ids.get(2)));
    }

    @Test
    @DisplayName("R9.4 전체 목록도 (createdAt desc, id desc) 순서")
    void globalOrdering() {
        long productId = api.createProduct(1_000, 100);
        api.createOrderId(newUserId(), productId, 1);
        api.createOrderId(newUserId(), productId, 1);

        JsonNode page = api.get("/api/orders?size=50").assertStatus(200).body();

        List<JsonNode> content = new ArrayList<>();
        page.get("content").forEach(content::add);
        Comparator<JsonNode> expected = Comparator
                .comparing((JsonNode o) -> Instant.parse(o.get("createdAt").asText())).reversed()
                .thenComparing(o -> o.get("id").asLong(), Comparator.reverseOrder());
        assertThat(content).isSortedAccordingTo(expected);
    }

    @Test
    @DisplayName("R9.2 userId·status 필터(AND)")
    void filters() {
        long productId = api.createProduct(1_000, 100);
        String alice = newUserId();
        String bob = newUserId();
        long alicePending = api.createOrderId(alice, productId, 1);
        long alicePaid = api.createOrderId(alice, productId, 1);
        api.pay(alicePaid).assertStatus(200);
        long bobPaid = api.createOrderId(bob, productId, 1);
        api.pay(bobPaid).assertStatus(200);

        assertThat(ids(api.get("/api/orders?userId=" + alice).assertStatus(200).body()))
                .containsExactly(alicePaid, alicePending);
        assertThat(ids(api.get("/api/orders?userId=" + alice + "&status=PAID").assertStatus(200).body()))
                .containsExactly(alicePaid);
        JsonNode paid = api.get("/api/orders?status=PAID&size=100").assertStatus(200).body();
        paid.get("content").forEach(o -> assertThat(o.get("status").asText()).isEqualTo("PAID"));
        assertThat(ids(paid)).contains(bobPaid, alicePaid).doesNotContain(alicePending);
    }

    @Test
    @DisplayName("R9.3 size 기본 20, cursor로 다음 페이지")
    void defaultSizeAndCursor() {
        long productId = api.createProduct(1_000, 100);
        String user = newUserId();
        for (int i = 0; i < 21; i++) {
            api.createOrderId(user, productId, 1);
        }

        JsonNode first = api.get("/api/orders?userId=" + user).assertStatus(200).body();
        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("nextCursor").isNull()).isFalse();

        JsonNode second = api.get("/api/orders?userId=" + user + "&cursor=" + first.get("nextCursor").asText())
                .assertStatus(200).body();
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.2·R9.3 잘못된 status·size·cursor → 400")
    void invalidQuery_returns400() {
        api.get("/api/orders?status=UNKNOWN").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?size=0").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?size=101").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?size=abc").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?cursor=not-a-cursor").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?cursor=%25%25%25").assertProblem(400, "VALIDATION_ERROR");
        api.get("/api/orders?size=1").assertStatus(200);
        api.get("/api/orders?size=100").assertStatus(200);
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 기존 주문은 중복·누락 없이 한 번씩")
    void stablePagination() {
        long productId = api.createProduct(1_000, 100);
        String user = newUserId();
        Set<Long> existing = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            existing.add(api.createOrderId(user, productId, 1));
        }

        List<Long> seen = new ArrayList<>();
        JsonNode page = api.get("/api/orders?userId=" + user + "&size=3").assertStatus(200).body();
        seen.addAll(ids(page));
        while (!page.get("nextCursor").isNull()) {
            api.createOrderId(user, productId, 1); // 페이지 사이에 새 주문
            page = api.get("/api/orders?userId=" + user + "&size=3&cursor=" + page.get("nextCursor").asText())
                    .assertStatus(200).body();
            seen.addAll(ids(page));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(existing);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }
}
