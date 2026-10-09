package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("R9 주문 목록")
class R09OrderListTest extends AbstractIntegrationTest {

    /** 한 사용자의 주문을 n건 순차 생성하고 id 를 생성 순서대로 돌려준다. */
    private List<Long> createOrders(String user, int n, long productId) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(newOrder(user, null, productId, 1).id());
        }
        return ids;
    }

    private static List<Long> ids(JsonNode content) {
        List<Long> out = new ArrayList<>();
        content.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    /** 모든 페이지를 따라가며 id 를 모은다. */
    private List<Long> collectAll(String query, int size) {
        List<Long> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            ApiResponse r = listOrders(query + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).isEqualTo(200);
            all.addAll(ids(r.json("content")));
            if (r.json("nextCursor").isNull()) {
                return all;
            }
            cursor = r.json("nextCursor").asText();
        }
        throw new AssertionError("pagination did not terminate");
    }

    // ------------------------------------------------------------- R9.1 shape

    @Test
    @DisplayName("R9.1 {content[], nextCursor} 형태, content 원소는 R3.5 형태, nextCursor 키는 항상 존재")
    void list_shape() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        createOrders(user, 2, p);

        ApiResponse r = listOrders("userId=" + user);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().fieldNames()).toIterable().containsExactlyInAnyOrder("content", "nextCursor");
        assertThat(r.json("nextCursor").isNull()).isTrue();
        assertThat(r.json("content")).hasSize(2);
        assertThat(r.json("content").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId",
                "status", "items", "couponCode", "subtotal", "discount", "totalPrice", "createdAt", "expiresAt",
                "paidAt");
        assertThat(r.json("content").get(0).get("items").get(0).get("productId").asLong()).isEqualTo(p);
    }

    @Test
    @DisplayName("R9.1 결과가 없으면 content=[] , nextCursor=null")
    void list_empty() {
        ApiResponse r = listOrders("userId=" + uniqueUser());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("content")).isEmpty();
        assertThat(r.json("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.1 목록 원소는 GET /api/orders/{id} 와 같은 내용")
    void list_elementEqualsGet() {
        long p = newProduct(1_000, 10);
        String user = uniqueUser();
        ApiResponse created = newOrder(user, null, p, 3);

        JsonNode element = listOrders("userId=" + user).json("content").get(0);

        assertThat(element.get("id").asLong()).isEqualTo(created.id());
        assertThat(element.get("subtotal").asLong()).isEqualTo(3_000);
        assertThat(Instant.parse(element.get("createdAt").asText()))
                .isEqualTo(instant(getOrder(created.id()).json("createdAt")));
    }

    // ------------------------------------------------------------- R9.2 filters

    @Test
    @DisplayName("R9.2 userId 필터는 해당 사용자의 주문만 돌려준다")
    void filter_byUserId() {
        long p = newProduct(1_000, 20);
        String a = uniqueUser();
        String b = uniqueUser();
        List<Long> aIds = createOrders(a, 3, p);
        createOrders(b, 2, p);

        ApiResponse r = listOrders("userId=" + a);

        assertThat(ids(r.json("content"))).containsExactlyInAnyOrderElementsOf(aIds);
        r.json("content").forEach(n -> assertThat(n.get("userId").asText()).isEqualTo(a));
    }

    @Test
    @DisplayName("R9.2 status 필터는 해당 상태의 주문만, userId 와 함께 주면 AND")
    void filter_byStatus_andAnd() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        String other = uniqueUser();
        ApiResponse pending = newOrder(user, null, p, 1);
        ApiResponse cancelled = newOrder(user, null, p, 1);
        cancel(cancelled.id());
        ApiResponse paid = newPaidOrder(user, null, p, 1);
        ApiResponse otherCancelled = newOrder(other, null, p, 1);
        cancel(otherCancelled.id());

        List<Long> both = ids(listOrders("userId=" + user + "&status=CANCELLED").json("content"));
        List<Long> userPending = ids(listOrders("userId=" + user + "&status=PENDING_PAYMENT").json("content"));
        List<Long> userPaid = ids(listOrders("userId=" + user + "&status=PAID").json("content"));
        List<Long> allUser = ids(listOrders("userId=" + user).json("content"));
        ApiResponse onlyStatus = listOrders("status=CANCELLED&size=100");

        assertThat(both).containsExactly(cancelled.id());
        assertThat(userPending).containsExactly(pending.id());
        assertThat(userPaid).containsExactly(paid.id());
        assertThat(allUser).containsExactlyInAnyOrder(pending.id(), cancelled.id(), paid.id());
        assertThat(ids(onlyStatus.json("content"))).contains(cancelled.id(), otherCancelled.id());
        onlyStatus.json("content").forEach(n -> assertThat(n.get("status").asText()).isEqualTo("CANCELLED"));
    }

    @Test
    @DisplayName("R9.2 userId 와 status 가 겹치지 않으면 빈 결과")
    void filter_andWithNoMatch() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        newOrder(user, null, p, 1);

        ApiResponse r = listOrders("userId=" + user + "&status=DELIVERED");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("content")).isEmpty();
    }

    @ParameterizedTest(name = "R9.2 정의되지 않은 status [{0}] -> 400")
    @ValueSource(strings = {"BOGUS", "paid", "PENDING", "CANCELED"})
    void filter_invalidStatus_400(String status) {
        ApiResponse r = listOrders("status=" + status);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(r.isProblemJson()).isTrue();
    }

    @Test
    @DisplayName("R9.2 정의된 8개 status 값은 모두 400 이 아니다")
    void filter_allDefinedStatusesAccepted() {
        for (String s : new String[] {"PENDING_PAYMENT", "PAID", "PAYMENT_FAILED", "EXPIRED", "CANCELLED", "REFUNDED",
                "SHIPPED", "DELIVERED"}) {
            assertThat(listOrders("userId=" + uniqueUser() + "&status=" + s).status()).as(s).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------- R9.3 size / cursor

    @Test
    @DisplayName("R9.3 size 기본값은 20 (22건 -> 20건 + nextCursor, 다음 페이지 2건 + null)")
    void size_defaultIs20() {
        long p = newProduct(1_000, 100);
        String user = uniqueUser();
        createOrders(user, 22, p);

        ApiResponse first = listOrders("userId=" + user);
        ApiResponse second = listOrders("userId=" + user + "&cursor=" + first.json("nextCursor").asText());

        assertThat(first.json("content")).hasSize(20);
        assertThat(first.json("nextCursor").isNull()).isFalse();
        assertThat(second.json("content")).hasSize(2);
        assertThat(second.json("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest(name = "R9.3 size={0} -> 400")
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5", "1000"})
    void size_outOfRange_400(String size) {
        ApiResponse r = listOrders("size=" + size);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R9.3 size 1 과 100 은 허용, size 만큼만 돌려준다")
    void size_boundariesAccepted() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        createOrders(user, 3, p);

        ApiResponse one = listOrders("userId=" + user + "&size=1");
        ApiResponse hundred = listOrders("userId=" + user + "&size=100");

        assertThat(one.status()).isEqualTo(200);
        assertThat(one.json("content")).hasSize(1);
        assertThat(one.json("nextCursor").isNull()).isFalse();
        assertThat(hundred.status()).isEqualTo(200);
        assertThat(hundred.json("content")).hasSize(3);
        assertThat(hundred.json("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.3 size=100 요청은 최대 100건까지 한 페이지로 돌려준다 (cursor 가 있으면 101번째가 다음 페이지)")
    void size_100_pagesAt100() {
        ApiResponse r = listOrders("size=100");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json("content").size()).isLessThanOrEqualTo(100);
    }

    @ParameterizedTest(name = "R9.3 해석할 수 없는 cursor [{0}] -> 400")
    @ValueSource(strings = {"garbage", "!!!", "aGVsbG8", "djE6YWJjOmRlZg", "djE6MTIzOi00", "djI6MTIzOjQ1"})
    void cursor_unparsable_400(String cursor) {
        // aGVsbG8="hello", djE6YWJjOmRlZg="v1:abc:def", djE6MTIzOi00="v1:123:-4", djI6MTIzOjQ1="v2:123:45"
        ApiResponse r = listOrders("cursor=" + cursor);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(r.isProblemJson()).isTrue();
    }

    @Test
    @DisplayName("R9.3 long 을 넘는 숫자가 든 cursor 도 400")
    void cursor_overflow_400() {
        String raw = "v1:99999999999999999999999:5";
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThat(listOrders("cursor=" + cursor).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------- R9.4 order

    @Test
    @DisplayName("R9.4 createdAt 내림차순 (최신 주문이 먼저)")
    void sort_createdAtDesc() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        List<Long> created = createOrders(user, 5, p);

        ApiResponse r = listOrders("userId=" + user);

        List<Long> expected = new ArrayList<>(created);
        Collections.reverse(expected);
        assertThat(ids(r.json("content"))).containsExactlyElementsOf(expected);
        List<Instant> times = new ArrayList<>();
        r.json("content").forEach(n -> times.add(instant(n.get("createdAt"))));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("R9.4 createdAt 이 같으면 id 내림차순 (페이지 경계가 동점 한가운데여도 중복·누락 없음)")
    void sort_idDescOnCreatedAtTie_acrossPages() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        List<Long> created = createOrders(user, 5, p);
        jdbc.update("update orders set created_at = date_trunc('milliseconds', now()), "
                + "expires_at = date_trunc('milliseconds', now()) + interval '1 hour' where user_id = ?", user);
        List<Long> expected = new ArrayList<>(created);
        expected.sort(Collections.reverseOrder());

        List<Long> unpaged = ids(listOrders("userId=" + user).json("content"));
        List<Long> paged = collectAll("userId=" + user, 2);

        assertThat(unpaged).containsExactlyElementsOf(expected);
        assertThat(paged).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("R9.4 / R9.1 마지막 페이지의 nextCursor 는 null (건수가 size 의 배수여도)")
    void lastPage_nextCursorNull_whenExactMultiple() {
        long p = newProduct(1_000, 20);
        String user = uniqueUser();
        createOrders(user, 6, p);

        ApiResponse first = listOrders("userId=" + user + "&size=3");
        ApiResponse second = listOrders("userId=" + user + "&size=3&cursor=" + first.json("nextCursor").asText());

        assertThat(first.json("content")).hasSize(3);
        assertThat(first.json("nextCursor").isNull()).isFalse();
        assertThat(second.json("content")).hasSize(3);
        assertThat(second.json("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R9.4 페이지를 이어 붙이면 전체 정렬 순서와 같다")
    void paging_concatEqualsFullSortedList() {
        long p = newProduct(1_000, 30);
        String user = uniqueUser();
        List<Long> created = createOrders(user, 9, p);
        List<Long> expected = new ArrayList<>(created);
        Collections.reverse(expected);

        assertThat(collectAll("userId=" + user, 4)).containsExactlyElementsOf(expected);
        assertThat(collectAll("userId=" + user, 1)).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------- R9.5 stability

    @Test
    @DisplayName("R9.5 페이지를 넘기는 중 새 주문이 생겨도 첫 페이지 시점의 주문이 중복·누락 없이 한 번씩 나온다")
    void paging_stableWhileNewOrdersArrive() {
        long p = newProduct(1_000, 50);
        String user = uniqueUser();
        List<Long> snapshot = createOrders(user, 7, p);
        List<Long> seen = new ArrayList<>();

        ApiResponse page = listOrders("userId=" + user + "&size=3");
        seen.addAll(ids(page.json("content")));
        List<Long> arrivedLater = new ArrayList<>();
        while (!page.json("nextCursor").isNull()) {
            arrivedLater.addAll(createOrders(user, 2, p)); // 페이지 사이마다 새 주문 도착
            page = listOrders("userId=" + user + "&size=3&cursor=" + page.json("nextCursor").asText());
            seen.addAll(ids(page.json("content")));
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(snapshot);
        assertThat(seen.stream().filter(snapshot::contains).toList()).hasSameSizeAs(snapshot);
        // 첫 페이지 이후 만들어진 주문은 커서보다 최신이라 이어지는 페이지에 나오지 않는다
        assertThat(seen).doesNotContainAnyElementsOf(arrivedLater);
        List<Long> expectedOrder = new ArrayList<>(snapshot);
        Collections.reverse(expectedOrder);
        assertThat(seen).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    @DisplayName("R9.5 페이지를 넘기는 중 기존 주문의 상태가 바뀌어도(취소) 중복·누락 없이 한 번씩")
    void paging_stableWhileOrdersChangeState() {
        long p = newProduct(1_000, 50);
        String user = uniqueUser();
        List<Long> snapshot = createOrders(user, 6, p);
        List<Long> seen = new ArrayList<>();

        ApiResponse page = listOrders("userId=" + user + "&size=2");
        seen.addAll(ids(page.json("content")));
        cancel(snapshot.get(0));
        cancel(snapshot.get(3));
        while (!page.json("nextCursor").isNull()) {
            page = listOrders("userId=" + user + "&size=2&cursor=" + page.json("nextCursor").asText());
            seen.addAll(ids(page.json("content")));
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(snapshot);
    }
}
