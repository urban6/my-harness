package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R6(주문 목록). 매 테스트 전 TRUNCATE 되므로 totalElements는 해당 테스트가 만든 주문 수와 같다. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OrderListApiTest extends AbstractIntegrationTest {

    /** createdAt을 직접 지정해 주문을 삽입한다 (동률 정렬 검증용). 생성된 id를 돌려준다. */
    private long insertOrderAt(long productId, Instant createdAt) {
        Long orderId = jdbc.queryForObject(
                "INSERT INTO orders (status, total_price, created_at) VALUES ('ORDERED', 1000, ?) RETURNING id",
                Long.class, Timestamp.from(createdAt));
        jdbc.update("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, 1, 1000)",
                orderId, productId);
        return orderId;
    }

    @Test
    @DisplayName("R6: 파라미터 없이 호출하면 page=0,size=20이 반영된 {content,page,size,totalElements} 응답")
    void r6_list_defaults_page0_size20() {
        long a = createProduct("A", 1000, 100);
        for (int i = 0; i < 3; i++) {
            createOrder(item(a, 1));
        }

        ResponseEntity<Map> res = get("/api/orders");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("content", "page", "size", "totalElements");
        assertThat(num(res.getBody(), "page")).isZero();
        assertThat(num(res.getBody(), "size")).isEqualTo(20L);
        assertThat(num(res.getBody(), "totalElements")).isEqualTo(3L);
        assertThat((List<Map>) res.getBody().get("content")).hasSize(3);
    }

    @Test
    @DisplayName("R6: 기본 size 20이 실제로 적용되어 25건 중 20건만 반환한다")
    void r6_list_defaultSize20_limitsContent() {
        long a = createProduct("A", 1000, 100);
        for (int i = 0; i < 25; i++) {
            createOrder(item(a, 1));
        }

        Map body = get("/api/orders").getBody();

        assertThat((List<Map>) body.get("content")).hasSize(20);
        assertThat(num(body, "totalElements")).isEqualTo(25L);
    }

    @Test
    @DisplayName("R6: 주문이 없으면 200 + 빈 content + totalElements 0")
    void r6_list_empty() {
        ResponseEntity<Map> res = get("/api/orders");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>) res.getBody().get("content")).isEmpty();
        assertThat(num(res.getBody(), "totalElements")).isZero();
    }

    @Test
    @DisplayName("R6: content 원소는 R4(주문 조회) 응답과 같은 형태·값이다")
    void r6_list_contentElementEqualsR4Shape() {
        long a = createProduct("A", 1000, 10);
        long b = createProduct("B", 300, 10);
        Map<String, Object> created = createOrder(item(a, 2), item(b, 1));

        Map element = ((List<Map>) get("/api/orders").getBody().get("content")).get(0);

        assertThat(element).containsOnlyKeys("id", "status", "totalPrice", "items", "createdAt");
        assertThat(element).isEqualTo(get("/api/orders/" + num(created, "id")).getBody());
    }

    @Test
    @DisplayName("R6: page/size가 반영되어 페이지별로 나뉘고 totalElements는 전체 건수")
    void r6_list_pagination_respectsPageAndSize() {
        long a = createProduct("A", 1000, 100);
        List<Long> idsInCreationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            idsInCreationOrder.add(num(createOrder(item(a, 1)), "id"));
        }

        Map page1 = get("/api/orders?page=1&size=2").getBody();
        Map page2 = get("/api/orders?page=2&size=2").getBody();

        assertThat(num(page1, "page")).isEqualTo(1L);
        assertThat(num(page1, "size")).isEqualTo(2L);
        assertThat(num(page1, "totalElements")).isEqualTo(5L);
        // 최신순: 생성 순서의 역순 [4,3 | 2,1 | 0]
        assertThat(((List<Map>) page1.get("content")).stream().map(m -> num(m, "id")).toList())
                .containsExactly(idsInCreationOrder.get(2), idsInCreationOrder.get(1));
        assertThat(((List<Map>) page2.get("content")).stream().map(m -> num(m, "id")).toList())
                .containsExactly(idsInCreationOrder.get(0));
    }

    @Test
    @DisplayName("R6: createdAt DESC로 정렬된다 (서로 다른 시각)")
    void r6_list_sortedByCreatedAtDesc() {
        long a = createProduct("A", 1000, 10);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // id 순서와 시각 순서를 어긋나게 삽입: 시각이 가장 늦은 주문의 id가 가장 작다.
        long latest = insertOrderAt(a, base.plusSeconds(300));
        long oldest = insertOrderAt(a, base);
        long middle = insertOrderAt(a, base.plusSeconds(100));

        List<Map> content = (List<Map>) get("/api/orders").getBody().get("content");

        assertThat(content.stream().map(m -> num(m, "id")).toList()).containsExactly(latest, middle, oldest);
    }

    @Test
    @DisplayName("R6: createdAt이 같으면 id DESC로 정렬된다")
    void r6_list_tieBrokenByIdDesc() {
        long a = createProduct("A", 1000, 10);
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        long first = insertOrderAt(a, same);
        long second = insertOrderAt(a, same);
        long third = insertOrderAt(a, same);

        List<Map> content = (List<Map>) get("/api/orders").getBody().get("content");

        assertThat(content.stream().map(m -> num(m, "id")).toList()).containsExactly(third, second, first);
    }

    @Test
    @DisplayName("R6: 실제 생성한 주문 목록이 (createdAt DESC, id DESC) 순서를 만족한다")
    void r6_list_realOrders_orderedByCreatedAtThenIdDesc() {
        long a = createProduct("A", 1000, 100);
        for (int i = 0; i < 6; i++) {
            createOrder(item(a, 1));
        }

        List<Map> content = (List<Map>) get("/api/orders?size=100").getBody().get("content");

        assertThat(content).hasSize(6);
        for (int i = 0; i < content.size() - 1; i++) {
            Instant cur = Instant.parse((String) content.get(i).get("createdAt"));
            Instant next = Instant.parse((String) content.get(i + 1).get("createdAt"));
            assertThat(cur).isAfterOrEqualTo(next);
            if (cur.equals(next)) {
                assertThat(num(content.get(i), "id")).isGreaterThan(num(content.get(i + 1), "id"));
            }
        }
    }

    @Test
    @DisplayName("R6: 경계값 page=0,size=1 및 size=100은 허용된다")
    void r6_list_boundaryValuesAllowed() {
        assertThat(get("/api/orders?page=0&size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/orders?page=0&size=100").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("R6: 데이터보다 큰 page는 200 + 빈 content")
    void r6_list_pageBeyondData_returnsEmptyContent() {
        long a = createProduct("A", 1000, 10);
        createOrder(item(a, 1));

        Map body = get("/api/orders?page=5&size=20").getBody();

        assertThat((List<Map>) body.get("content")).isEmpty();
        assertThat(num(body, "totalElements")).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "page=-1",
            "size=0",
            "size=101",
            "size=-5",
            "page=-1&size=0",
            "page=abc",
            "size=abc",
            "size=1.5",
            "page=99999999999"
    })
    @DisplayName("R6: 범위 밖이거나 숫자가 아닌 page/size는 400")
    void r6_list_invalidParams_returns400(String query) {
        assertProblem(get("/api/orders?" + query), HttpStatus.BAD_REQUEST);
    }
}
