package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-09 order list: cursor pagination")
class OrderListTest extends IntegrationTestBase {

    private List<Long> createOrders(String user, int count) {
        long p = api.newProduct(100, 10_000);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(api.orderOk(user, p, 1, null).get("id").asLong());
        }
        return ids;
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        return ids;
    }

    @Test
    @DisplayName("REQ-09 userId 필터 목록은 id 내림차순이고 마지막 페이지의 nextCursor 는 null")
    void listsNewestFirstWithNullNextCursorOnSinglePage() {
        String user = ApiClient.uniq("lister");
        List<Long> created = createOrders(user, 5);
        createOrders(ApiClient.uniq("other"), 2);

        ResponseEntity<JsonNode> res = api.get("/api/orders?userId=" + user);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(idsOf(res.getBody())).containsExactlyElementsOf(created.reversed());
        assertThat(res.getBody().has("nextCursor")).isTrue();
        assertThat(res.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("REQ-09 커서를 따라가면 중복/누락 없이 전체를 정렬 순서대로 순회하고 마지막에 nextCursor=null")
    void cursorWalkVisitsEveryOrderExactlyOnce() {
        String user = ApiClient.uniq("walker");
        List<Long> created = createOrders(user, 5);

        List<Long> visited = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            JsonNode page = api.get("/api/orders?userId=" + user + "&size=2" + (cursor == null ? "" : "&cursor=" + cursor))
                    .getBody();
            visited.addAll(idsOf(page));
            pageSizes.add(page.get("content").size());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null && ++guard < 10);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(visited).containsExactlyElementsOf(created.reversed());
    }

    @Test
    @DisplayName("REQ-09 size 가 정확히 결과 수와 같으면 nextCursor=null (LIMIT size+1 판별)")
    void exactFitPageHasNoNextCursor() {
        String user = ApiClient.uniq("exact");
        createOrders(user, 3);

        JsonNode page = api.get("/api/orders?userId=" + user + "&size=3").getBody();

        assertThat(page.get("content")).hasSize(3);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("REQ-09 size = 결과 수 - 1 이면 nextCursor 가 있고, 마지막 항목 id 로 인코딩된 커서다")
    void nextCursorPointsAtLastReturnedItem() {
        String user = ApiClient.uniq("cur");
        List<Long> created = createOrders(user, 3);

        JsonNode page = api.get("/api/orders?userId=" + user + "&size=2").getBody();

        assertThat(page.get("nextCursor").asText()).isEqualTo(b64("o:" + created.get(1)));
    }

    @Test
    @DisplayName("REQ-09 기본 size 는 20 (21건 중 20건 + nextCursor)")
    void defaultPageSizeIsTwenty() {
        String user = ApiClient.uniq("def");
        createOrders(user, 21);

        JsonNode page = api.get("/api/orders?userId=" + user).getBody();

        assertThat(page.get("content")).hasSize(20);
        assertThat(page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    @DisplayName("REQ-09 size 경계 1 과 100 은 허용")
    void sizeBoundariesAreAccepted() {
        String user = ApiClient.uniq("sz");
        createOrders(user, 2);

        JsonNode one = api.get("/api/orders?userId=" + user + "&size=1").getBody();
        ResponseEntity<JsonNode> hundred = api.get("/api/orders?userId=" + user + "&size=100");

        assertThat(one.get("content")).hasSize(1);
        assertThat(one.get("nextCursor").isNull()).isFalse();
        assertThat(hundred.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hundred.getBody().get("content")).hasSize(2);
    }

    @ParameterizedTest(name = "size={0}")
    @ValueSource(strings = { "0", "-1", "101", "abc", "1.5", "" })
    @DisplayName("REQ-09 잘못된 size -> 400 invalid-parameter (parameter=size), clamp 하지 않음")
    void invalidSizeIsRejected(String size) {
        ResponseEntity<JsonNode> res = api.get("/api/orders?size=" + size);

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("parameter").asText()).isEqualTo("size");
    }

    @ParameterizedTest(name = "cursor={0}")
    @ValueSource(strings = { "!!!not-base64!!!", "eDo1" /* x:5 */, "bzphYmM" /* o:abc */, "bzow" /* o:0 */,
            "bzotMw" /* o:-3 */, "bzo" /* truncated */ })
    @DisplayName("REQ-09 잘못된 cursor -> 400 invalid-parameter (parameter=cursor)")
    void invalidCursorIsRejected(String cursor) {
        ResponseEntity<JsonNode> res = api.get("/api/orders?cursor=" + cursor);

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("parameter").asText()).isEqualTo("cursor");
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = { "BOGUS", "paid", "PENDING" })
    @DisplayName("REQ-09 잘못된 status -> 400 invalid-parameter (parameter=status)")
    void invalidStatusIsRejected(String status) {
        ResponseEntity<JsonNode> res = api.get("/api/orders?status=" + status);

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("parameter").asText()).isEqualTo("status");
    }

    @Test
    @DisplayName("REQ-09 빈 userId -> 400 invalid-parameter (parameter=userId)")
    void emptyUserIdIsRejected() {
        ResponseEntity<JsonNode> res = api.get("/api/orders?userId=");

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("parameter").asText()).isEqualTo("userId");
    }

    @Test
    @DisplayName("REQ-09 status 필터는 해당 상태만 반환한다")
    void statusFilterReturnsOnlyThatStatus() {
        String user = ApiClient.uniq("flt");
        long p = api.newProduct(100, 100);
        long pending = api.orderOk(user, p, 1, null).get("id").asLong();
        long paid = api.orderOk(user, p, 1, null).get("id").asLong();
        long cancelled = api.orderOk(user, p, 1, null).get("id").asLong();
        api.payOk(paid);
        api.cancel(cancelled);

        JsonNode paidPage = api.get("/api/orders?userId=" + user + "&status=PAID").getBody();
        JsonNode pendingPage = api.get("/api/orders?userId=" + user + "&status=PENDING_PAYMENT").getBody();
        JsonNode cancelledPage = api.get("/api/orders?userId=" + user + "&status=CANCELLED").getBody();
        JsonNode shippedPage = api.get("/api/orders?userId=" + user + "&status=SHIPPED").getBody();

        assertThat(idsOf(paidPage)).containsExactly(paid);
        assertThat(idsOf(pendingPage)).containsExactly(pending);
        assertThat(idsOf(cancelledPage)).containsExactly(cancelled);
        assertThat(shippedPage.get("content")).isEmpty();
        assertThat(shippedPage.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("REQ-09 결과 없음 -> content=[] , nextCursor=null")
    void unknownUserYieldsEmptyPage() {
        ResponseEntity<JsonNode> res = api.get("/api/orders?userId=" + ApiClient.uniq("ghost"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("content")).isEmpty();
        assertThat(res.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("REQ-09 필터 없는 목록도 200 이며 content 항목은 주문 전체 스키마, size 이하")
    void unfilteredListWorksAndItemsHaveFullSchema() {
        createOrders(ApiClient.uniq("any"), 1);

        ResponseEntity<JsonNode> res = api.get("/api/orders?size=5");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("content").size()).isBetween(1, 5);
        JsonNode first = res.getBody().get("content").get(0);
        for (String f : List.of("id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt")) {
            assertThat(first.has(f)).as(f).isTrue();
        }
        assertThat(first.get("items")).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-09 페이지 사이에 새 주문이 생겨도 다음 페이지에 중복/누락이 없다 (안정 커서)")
    void newOrdersBetweenPagesDoNotShiftTheCursor() {
        String user = ApiClient.uniq("stable");
        List<Long> created = createOrders(user, 4);
        JsonNode first = api.get("/api/orders?userId=" + user + "&size=2").getBody();
        createOrders(user, 2);

        JsonNode second = api.get("/api/orders?userId=" + user + "&size=2&cursor=" + first.get("nextCursor").asText())
                .getBody();

        assertThat(idsOf(first)).containsExactly(created.get(3), created.get(2));
        assertThat(idsOf(second)).containsExactly(created.get(1), created.get(0));
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }
}
