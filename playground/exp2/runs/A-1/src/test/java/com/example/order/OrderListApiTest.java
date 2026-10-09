package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

@DisplayName("R9. 주문 목록")
class OrderListApiTest extends IntegrationTestSupport {

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }

    private List<Long> createOrders(String user, long productId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(placeOrder(user, null, item(productId, 1)).get("id").asLong());
        }
        return ids;
    }

    @Test
    @DisplayName("R9.1 {content[], nextCursor}, 원소는 주문 조회와 같은 형태, 다음 페이지가 없으면 nextCursor null")
    void shape() {
        long p = createProduct(1_000, 100);
        String user = newUser();
        long orderId = placeOrder(user, null, item(p, 2)).get("id").asLong();

        ResponseEntity<JsonNode> response = get("/api/orders?userId=" + user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("content")).hasSize(1);
        assertThat(body.get("content").get(0)).isEqualTo(order(orderId));
        assertThat(body.has("nextCursor")).isTrue();
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.2 userId·status 필터, 둘 다 주면 AND")
    void filters() {
        long p = createProduct(1_000, 100);
        String alice = newUser();
        String bob = newUser();
        long alicePending = placeOrder(alice, null, item(p, 1)).get("id").asLong();
        long alicePaid = paidOrder(alice, null, item(p, 1)).get("id").asLong();
        long bobPaid = paidOrder(bob, null, item(p, 1)).get("id").asLong();

        assertThat(ids(get("/api/orders?userId=" + alice).getBody())).containsExactly(alicePaid, alicePending);
        assertThat(ids(get("/api/orders?userId=" + alice + "&status=PAID").getBody())).containsExactly(alicePaid);
        assertThat(ids(get("/api/orders?userId=" + bob + "&status=PENDING_PAYMENT").getBody())).isEmpty();

        JsonNode paid = get("/api/orders?status=PAID&size=100").getBody();
        assertThat(paid.get("content")).allMatch(o -> o.get("status").asText().equals("PAID"));
        assertThat(ids(paid)).contains(bobPaid, alicePaid).doesNotContain(alicePending);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "status=UNKNOWN", "status=paid", "size=0", "size=101", "size=-1", "size=abc",
            "cursor=!!!", "cursor=Zm9v", "cursor=MTIzOmFiYw"})
    @DisplayName("R9.2/R9.3 정의되지 않은 status, 범위 밖 size, 해석할 수 없는 cursor는 400")
    void invalidQuery(String query) {
        assertProblem(get("/api/orders?" + query), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 size 기본 20, 최대 100")
    void pageSize() {
        long p = createProduct(1_000, 1_000);
        String user = newUser();
        createOrders(user, p, 21);

        JsonNode first = get("/api/orders?userId=" + user).getBody();
        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("nextCursor").isNull()).isFalse();

        JsonNode second = get("/api/orders?userId=" + user + "&cursor=" + first.get("nextCursor").asText()).getBody();
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();

        assertThat(get("/api/orders?userId=" + user + "&size=100").getBody().get("content")).hasSize(21);
        assertThat(get("/api/orders?userId=" + user + "&size=1").getBody().get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R9.4 createdAt 내림차순, 같으면 id 내림차순")
    void ordering() {
        long p = createProduct(1_000, 100);
        String user = newUser();
        List<Long> created = createOrders(user, p, 5);

        JsonNode page = get("/api/orders?userId=" + user).getBody();
        assertThat(ids(page)).containsExactlyElementsOf(created.reversed());

        Instant previous = Instant.MAX;
        for (JsonNode order : page.get("content")) {
            Instant createdAt = instant(order, "createdAt");
            assertThat(createdAt).isBeforeOrEqualTo(previous);
            previous = createdAt;
        }
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 첫 페이지 시점의 주문은 정확히 한 번씩 나온다")
    void stableCursor() {
        long p = createProduct(1_000, 100);
        String user = newUser();
        List<Long> existing = createOrders(user, p, 7);

        List<Long> seen = new ArrayList<>();
        JsonNode page = get("/api/orders?userId=" + user + "&size=3").getBody();
        seen.addAll(ids(page));
        while (!page.get("nextCursor").isNull()) {
            createOrders(user, p, 2); // 페이지 사이에 새 주문
            page = get("/api/orders?userId=" + user + "&size=3&cursor=" + page.get("nextCursor").asText()).getBody();
            seen.addAll(ids(page));
        }

        assertThat(seen).containsExactlyElementsOf(existing.reversed());
    }

    @Test
    @DisplayName("R9.5 필터 없는 전체 목록도 커서로 끝까지 넘기면 첫 페이지 시점 주문이 중복·누락 없이 나온다")
    void stableCursorWithoutFilter() {
        long p = createProduct(1_000, 100);
        String user = newUser();
        List<Long> mine = createOrders(user, p, 4);

        List<Long> seen = new ArrayList<>();
        JsonNode page = get("/api/orders?size=50").getBody();
        seen.addAll(ids(page));
        createOrders(user, p, 2);
        while (!page.get("nextCursor").isNull()) {
            page = get("/api/orders?size=50&cursor=" + page.get("nextCursor").asText()).getBody();
            seen.addAll(ids(page));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen.stream().filter(mine::contains).toList()).containsExactlyElementsOf(mine.reversed());
    }
}
