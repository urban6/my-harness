package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderListSmokeTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R9.1 R9.4 R9.5 커서 페이지네이션은 각 주문을 정확히 한 번, 내림차순으로 반환")
    void list_cursorPaging_returnsEachOrderExactlyOnce() {
        long productId = newProduct(1000, 100);
        String user = uniqueUser();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ApiResponse r = postOrder(user, uniqueKey(), orderJson(null, line(productId, 1)));
            assertThat(r.status()).isEqualTo(201);
            created.add(r.id());
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ApiResponse page = listOrders("userId=" + user + "&size=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            for (JsonNode n : page.json().get("content")) {
                seen.add(n.get("id").asLong());
            }
            JsonNode next = page.json().get("nextCursor");
            cursor = next.isNull() ? null : next.asText();
            if (++pages == 1) {
                // 첫 페이지 이후 새 주문이 생겨도 이어지는 페이지에는 영향이 없다 (R9.5)
                postOrder(user, uniqueKey(), orderJson(null, line(productId, 1)));
            }
        } while (cursor != null);

        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.reverseOrder()); // createdAt desc, id desc
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("R9.2 R9.3 필터와 size·status·cursor 검증")
    void list_filters_andValidation() {
        long productId = newProduct(1000, 100);
        String user = uniqueUser();
        long orderId = postOrder(user, uniqueKey(), orderJson(null, line(productId, 1))).id();
        postOrder(user, uniqueKey(), orderJson(null, line(productId, 1)));
        cancel(orderId);

        ApiResponse cancelled = listOrders("userId=" + user + "&status=CANCELLED");
        assertThat(cancelled.json().get("content")).hasSize(1);
        assertThat(cancelled.json().get("nextCursor").isNull()).isTrue();

        assertThat(listOrders("status=BOGUS").status()).isEqualTo(400);
        assertThat(listOrders("size=0").status()).isEqualTo(400);
        assertThat(listOrders("size=101").status()).isEqualTo(400);
        assertThat(listOrders("size=abc").status()).isEqualTo(400);
        assertThat(listOrders("cursor=not-a-cursor!!").status()).isEqualTo(400);
        assertThat(listOrders("userId=" + uniqueUser()).json().get("content")).isEmpty();
    }
}
