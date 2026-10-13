package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** R6 주문 목록. 정렬·개수 검증을 위해 매 테스트 전에 테이블을 비운다. */
class OrderListApiTest extends AbstractIntegrationTest {

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    private ResponseEntity<Map> list(String query) {
        return rest.getForEntity("/api/orders" + query, Map.class);
    }

    private static List<Map<String, Object>> content(ResponseEntity<Map> res) {
        return (List<Map<String, Object>>) res.getBody().get("content");
    }

    private static List<Long> ids(ResponseEntity<Map> res) {
        return content(res).stream().map(o -> ((Number) o.get("id")).longValue()).toList();
    }

    /** created_at을 직접 지정해 주문 행을 만든다(정렬 규칙 검증용). */
    private long insertOrder(Instant createdAt) {
        return jdbcTemplate.queryForObject(
                "insert into orders(status, total_price, created_at) values ('ORDERED', 1000, ?) returning id",
                Long.class, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("R6: 파라미터 없이 호출하면 page=0, size=20, 주문 수만큼 totalElements")
    void r6_list_defaults() {
        long p = createProduct("A", 1000, 100);
        postOrder(item(p, 1));
        postOrder(item(p, 1));
        postOrder(item(p, 1));

        ResponseEntity<Map> res = list("");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsOnlyKeys("content", "page", "size", "totalElements")
                .containsEntry("page", 0)
                .containsEntry("size", 20)
                .containsEntry("totalElements", 3);
        assertThat(content(res)).hasSize(3);
    }

    @Test
    @DisplayName("R6: content 원소는 R4 단건 조회 응답과 같은 형태·값이다")
    void r6_list_contentElementEqualsGetOrderBody() {
        long a = createProduct("A", 1000, 100);
        long b = createProduct("B", 500, 100);
        long orderId = idOf(postOrder(item(a, 2), item(b, 3)));

        ResponseEntity<Map> res = list("");

        assertThat(content(res)).hasSize(1);
        assertThat(content(res).get(0)).isEqualTo(getOrder(orderId).getBody());
    }

    @Test
    @DisplayName("R6: 취소된 주문도 목록에 CANCELLED 상태로 나온다")
    void r6_list_includesCancelledOrders() {
        long p = createProduct("A", 1000, 100);
        long orderId = idOf(postOrder(item(p, 1)));
        cancelOrder(orderId);

        ResponseEntity<Map> res = list("");

        assertThat(content(res)).singleElement().satisfies(o -> assertThat(o).containsEntry("status", "CANCELLED"));
    }

    @Test
    @DisplayName("R6: 정렬은 createdAt 내림차순이며, 같으면 id 내림차순이다")
    void r6_list_sortedByCreatedAtDescThenIdDesc() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        long a = insertOrder(base);                    // 가장 오래됨
        long b = insertOrder(base.plusSeconds(20));    // 가장 최신 (동률)
        long c = insertOrder(base.plusSeconds(10));    // 중간
        long d = insertOrder(base.plusSeconds(20));    // 가장 최신 (동률, b보다 id 큼)

        ResponseEntity<Map> res = list("");

        assertThat(ids(res)).containsExactly(d, b, c, a);
    }

    @Test
    @DisplayName("R6: 실제 API로 만든 주문은 최근 주문이 먼저 나오고 createdAt이 내림차순이다")
    void r6_list_apiCreatedOrders_newestFirst() {
        long p = createProduct("A", 1000, 100);
        long first = idOf(postOrder(item(p, 1)));
        long second = idOf(postOrder(item(p, 1)));
        long third = idOf(postOrder(item(p, 1)));

        ResponseEntity<Map> res = list("");

        assertThat(ids(res)).containsExactly(third, second, first);
        List<Instant> createdAts = content(res).stream().map(o -> Instant.parse((String) o.get("createdAt"))).toList();
        assertThat(createdAts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("R6: page/size로 잘라서 반환하고 totalElements는 전체 개수다")
    void r6_list_paging() {
        long p = createProduct("A", 1000, 100);
        for (int i = 0; i < 5; i++) {
            postOrder(item(p, 1));
        }
        List<Long> all = ids(list("?size=100"));
        assertThat(all).hasSize(5);

        ResponseEntity<Map> page1 = list("?page=1&size=2");

        assertThat(page1.getBody()).containsEntry("page", 1).containsEntry("size", 2)
                .containsEntry("totalElements", 5);
        assertThat(ids(page1)).containsExactly(all.get(2), all.get(3));
        assertThat(ids(list("?page=2&size=2"))).containsExactly(all.get(4));
    }

    @Test
    @DisplayName("R6: 범위를 넘는 page는 200 + 빈 content + 실제 totalElements")
    void r6_list_pageBeyondRange_returnsEmptyContent() {
        long p = createProduct("A", 1000, 100);
        postOrder(item(p, 1));

        ResponseEntity<Map> res = list("?page=1000");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(res)).isEmpty();
        assertThat(res.getBody()).containsEntry("totalElements", 1);
    }

    @Test
    @DisplayName("R6: 주문이 없으면 content 빈 배열, totalElements 0")
    void r6_list_empty() {
        ResponseEntity<Map> res = list("");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(res)).isEmpty();
        assertThat(res.getBody()).containsEntry("totalElements", 0);
    }

    @Test
    @DisplayName("R6: size=100은 허용된다 (상한 경계)")
    void r6_list_size100_isAllowed() {
        ResponseEntity<Map> res = list("?size=100");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("size", 100);
    }

    @Test
    @DisplayName("R6: size=1은 허용된다 (하한 경계)")
    void r6_list_size1_isAllowed() {
        ResponseEntity<Map> res = list("?size=1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("size", 1);
    }

    @ParameterizedTest(name = "R6: ?{0} -> 400")
    @ValueSource(strings = { "page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc" })
    @DisplayName("R6: 범위 밖/형식 오류 파라미터는 400 problem+json")
    void r6_list_invalidParams_return400(String query) {
        ResponseEntity<Map> res = list("?" + query);

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }
}
