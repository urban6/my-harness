package com.example.order;

import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R9. 주문 목록")
class OrderListTest extends IntegrationTest {

    private Resp list(String query) {
        return get("/api/orders?" + query);
    }

    private List<Long> placeOrders(String user, long productId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(placeOrder(user, null, item(productId, 1)).path("id").asLong());
        }
        return ids;
    }

    private static List<Long> ids(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(o -> ids.add(o.path("id").asLong()));
        return ids;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("R9.1 200 {content[], nextCursor}, content 원소는 R3.5 형태, 마지막 페이지의 nextCursor 는 null")
    void shape() {
        String user = uniqueUser();
        long productId = createProduct(1_000, 100);
        long orderId = placeOrder(user, null, item(productId, 2)).path("id").asLong();

        Resp r = list("userId=" + enc(user));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("content")).hasSize(1);
        assertThat(r.json().path("content").get(0)).isEqualTo(order(orderId));
        assertThat(r.json().has("nextCursor")).isTrue();
        assertThat(r.json().path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.2 userId·status 필터, 둘 다 주면 AND")
    void filters() {
        String user = uniqueUser();
        String other = uniqueUser();
        long productId = createProduct(1_000, 100);
        List<Long> mine = placeOrders(user, productId, 3);
        placeOrders(other, productId, 2);
        assertThat(pay(mine.get(1), "tok_ok").status()).isEqualTo(200);

        JsonNode byUser = list("userId=" + enc(user)).json().path("content");
        assertThat(ids(byUser)).containsExactlyInAnyOrderElementsOf(mine);

        JsonNode byBoth = list("userId=" + enc(user) + "&status=PAID").json().path("content");
        assertThat(ids(byBoth)).containsExactly(mine.get(1));

        JsonNode byStatus = list("status=PAID&size=100").json().path("content");
        assertThat(byStatus).isNotEmpty();
        byStatus.forEach(o -> assertThat(o.path("status").asText()).isEqualTo("PAID"));

        JsonNode pendingOfUser = list("userId=" + enc(user) + "&status=PENDING_PAYMENT").json().path("content");
        assertThat(ids(pendingOfUser)).containsExactlyInAnyOrder(mine.get(0), mine.get(2));
    }

    @Test
    @DisplayName("R9.4 createdAt 내림차순, 같으면 id 내림차순")
    void ordering() {
        String user = uniqueUser();
        List<Long> created = placeOrders(user, createProduct(1_000, 100), 5);

        JsonNode content = list("userId=" + enc(user)).json().path("content");

        assertThat(ids(content)).containsExactlyElementsOf(created.reversed());
        List<JsonNode> nodes = new ArrayList<>();
        content.forEach(nodes::add);
        Comparator<JsonNode> expected = Comparator.<JsonNode, Instant>comparing(o -> instant(o.path("createdAt")))
                .thenComparing(o -> o.path("id").asLong()).reversed();
        assertThat(nodes).isSortedAccordingTo(expected);
    }

    @Test
    @DisplayName("R9.3 size 기본값은 20")
    void defaultSize() {
        String user = uniqueUser();
        List<Long> created = placeOrders(user, createProduct(1_000, 100), 21);

        Resp first = list("userId=" + enc(user));
        assertThat(first.json().path("content")).hasSize(20);
        String cursor = first.json().path("nextCursor").asText(null);
        assertThat(cursor).isNotNull();

        Resp second = list("userId=" + enc(user) + "&cursor=" + enc(cursor));
        assertThat(ids(second.json().path("content"))).containsExactly(created.getFirst());
        assertThat(second.json().path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 기존 주문은 중복·누락 없이 정확히 한 번씩")
    void stablePagination() {
        String user = uniqueUser();
        long productId = createProduct(1_000, 100);
        List<Long> existing = placeOrders(user, productId, 7);

        List<Long> seen = new ArrayList<>();
        Resp page = list("userId=" + enc(user) + "&size=3");
        seen.addAll(ids(page.json().path("content")));
        while (!page.json().path("nextCursor").isNull()) {
            placeOrders(user, productId, 2); // 페이지 사이에 새 주문
            page = list("userId=" + enc(user) + "&size=3&cursor=" + enc(page.json().path("nextCursor").asText()));
            assertThat(page.status()).isEqualTo(200);
            seen.addAll(ids(page.json().path("content")));
        }

        assertThat(seen).containsExactlyElementsOf(existing.reversed());
    }

    @Test
    @DisplayName("R9.3 size 1·100 경계 허용")
    void sizeBoundaries() {
        String user = uniqueUser();
        placeOrders(user, createProduct(1_000, 100), 2);
        Resp one = list("userId=" + enc(user) + "&size=1");
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.json().path("content")).hasSize(1);
        assertThat(one.json().path("nextCursor").isNull()).isFalse();
        assertThat(list("userId=" + enc(user) + "&size=100").status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"status=UNKNOWN", "status=paid", "size=0", "size=101", "size=-1", "size=abc",
            "cursor=not-a-cursor", "cursor=%25%25%25", "cursor=djE6eDox"})
    @DisplayName("R9.2·R9.3 정의되지 않은 status, 범위 밖 size, 해석할 수 없는 cursor 는 400")
    void invalidQuery(String query) {
        assertProblem(list(query), 400, "VALIDATION_ERROR");
    }
}
