package com.example.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiResponse;
import com.example.order.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R9 주문 목록")
class OrderListApiTest extends IntegrationTest {

    @Test
    @DisplayName("R9.1/R9.4 userId 필터, createdAt·id 내림차순, 원소는 단건 조회 형태, 마지막 페이지 nextCursor null")
    void listByUser() {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(createOrder(user, null, List.of(item(productId, 1))));
        }
        createOrder(uniqueUser(), null, List.of(item(productId, 1))); // 다른 사용자

        ApiResponse res = api.get("/api/orders?userId=" + user);

        assertThat(res.status()).isEqualTo(200);
        JsonNode content = res.body().get("content");
        assertThat(ids(content)).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        assertThat(content.get(0)).isEqualTo(order(ids.get(2)));
        assertThat(res.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.2 userId·status 를 함께 주면 AND")
    void filterByUserAndStatus() {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        long paid = createOrder(user, null, List.of(item(productId, 1)));
        payOk(paid);
        createOrder(user, null, List.of(item(productId, 1)));
        long otherPaid = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        payOk(otherPaid);

        JsonNode content = api.get("/api/orders?userId=" + user + "&status=PAID").body().get("content");

        assertThat(ids(content)).containsExactly(paid);
    }

    @Test
    @DisplayName("R9.2 status 만 주면 그 상태의 주문만, 정렬 유지")
    void filterByStatusOnly() {
        long productId = createProduct(1_000, 100);
        long cancelled = createOrder(uniqueUser(), null, List.of(item(productId, 1)));
        action(cancelled, "cancel");

        ApiResponse res = api.get("/api/orders?status=CANCELLED&size=100");

        assertThat(res.status()).isEqualTo(200);
        List<JsonNode> content = new ArrayList<>();
        res.body().get("content").forEach(content::add);
        assertThat(content).isNotEmpty().allSatisfy(o -> assertThat(o.get("status").asText()).isEqualTo("CANCELLED"));
        assertThat(ids(res.body().get("content"))).contains(cancelled);
        assertThat(content).isSortedAccordingTo(Comparator
                .comparing((JsonNode o) -> OffsetDateTime.parse(o.get("createdAt").asText())).reversed()
                .thenComparing(o -> -o.get("id").asLong()));
    }

    @Test
    @DisplayName("R9.3 size 기본 20, nextCursor 로 다음 페이지")
    void defaultSizeAndPaging() {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        for (int i = 0; i < 21; i++) {
            createOrder(user, null, List.of(item(productId, 1)));
        }

        JsonNode first = api.get("/api/orders?userId=" + user).body();
        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("nextCursor").isTextual()).isTrue();

        JsonNode second = api.get("/api/orders?userId=" + user + "&cursor=" + first.get("nextCursor").asText()).body();
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.3 size 1·100 은 허용")
    void sizeBoundaries() {
        assertThat(api.get("/api/orders?size=1").status()).isEqualTo(200);
        assertThat(api.get("/api/orders?size=100").status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "status=UNKNOWN", "status=paid", "size=0", "size=101", "size=-1", "size=abc",
            "cursor=not-a-cursor", "cursor=%25%25%25"})
    @DisplayName("R9.2/R9.3 정의되지 않은 status, 범위 밖 size, 해석할 수 없는 cursor 는 400")
    void invalidQuery(String query) {
        assertProblem(api.get("/api/orders?" + query), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 base64 지만 내용이 커서가 아닌 값도 400")
    void invalidCursorContent() {
        String garbage = Base64.getUrlEncoder().encodeToString("hello|world".getBytes(StandardCharsets.UTF_8));
        assertProblem(api.get("/api/orders?cursor=" + garbage), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 사이 새 주문이 생겨도 기존 주문은 중복·누락 없이 정확히 한 번씩")
    void stableAcrossInserts() {
        long productId = createProduct(1_000, 100);
        String user = uniqueUser();
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            original.add(createOrder(user, null, List.of(item(productId, 1))));
        }

        List<Long> seen = new ArrayList<>();
        JsonNode page = api.get("/api/orders?userId=" + user + "&size=3").body();
        seen.addAll(ids(page.get("content")));
        for (int i = 0; i < 3; i++) {
            createOrder(user, null, List.of(item(productId, 1))); // 페이지 사이에 새 주문
        }
        while (!page.get("nextCursor").isNull()) {
            page = api.get("/api/orders?userId=" + user + "&size=3&cursor=" + page.get("nextCursor").asText()).body();
            seen.addAll(ids(page.get("content")));
            createOrder(user, null, List.of(item(productId, 1)));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(original.reversed());
    }

    @Test
    @DisplayName("R9.1 결과가 없으면 빈 content 와 null nextCursor")
    void empty() {
        JsonNode body = api.get("/api/orders?userId=" + uniqueUser()).body();
        assertThat(body.get("content")).isEmpty();
        assertThat(body.get("nextCursor").isNull()).isTrue();
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("content", "nextCursor");
    }

    private static List<Long> ids(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }
}
