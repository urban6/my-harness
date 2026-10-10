package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class OrderListCursorSmokeTest extends AbstractIntegrationTest {

    @Test
    void list_pagesByIdDescendingWithCursorAndEndsWithNullCursor() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(api.createOrder("u1", "k" + i, null, product, 1).get("id").asLong());
        }
        api.createOrder("u2", "k1", null, product, 1);

        JsonNode page1 = api.json(api.listOrders("userId=u1&size=2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(2))).andReturn());
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        assertThat(page1.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(3));
        String cursor1 = page1.get("nextCursor").asText();

        JsonNode page2 = api.json(api.listOrders("userId=u1&size=2&cursor=" + cursor1).andReturn());
        assertThat(page2.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(page2.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));

        JsonNode page3 = api.json(api.listOrders("userId=u1&size=2&cursor=" + page2.get("nextCursor").asText()).andReturn());
        assertThat(page3.get("content")).hasSize(1);
        assertThat(page3.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(0));
        assertThat(page3.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void list_filtersByStatus_andEmptyResultIsEmptyArray() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        long paid = api.createPaidOrder("u1", "k1", null, product, 1);
        api.createOrder("u1", "k2", null, product, 1);

        api.listOrders("userId=u1&status=PAID")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(paid))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        api.listOrders("userId=nobody")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        api.listOrders("").andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void list_invalidParameters_return400() throws Exception {
        api.listOrders("size=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.listOrders("size=101").andExpect(status().isBadRequest());
        api.listOrders("size=abc").andExpect(status().isBadRequest());
        api.listOrders("status=BOGUS").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
        api.listOrders("cursor=not-a-cursor!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-cursor"));
        api.listOrders("cursor=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("v2:5".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:invalid-cursor"));
    }

    @Test
    void list_showsLazilyExpiredOrdersAsExpired() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        api.createOrder("u1", "k1", null, product, 1);
        clock.advance(java.time.Duration.ofMinutes(16));

        api.listOrders("userId=u1")
                .andExpect(jsonPath("$.content[0].status").value("EXPIRED"));
        assertThat(reserved(product)).isZero();
    }

    @Test
    void list_statusExpired_includesOrdersPastTtlBeforeSchedulerRuns() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        api.createCoupon("C1", "FIXED", 10, null, null, 2);
        long orderId = api.createOrder("u1", "k1", "C1", product, 2).get("id").asLong();
        clock.advance(java.time.Duration.ofMinutes(16));

        api.listOrders("status=EXPIRED")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
        api.listOrders("status=EXPIRED").andExpect(jsonPath("$.content", hasSize(1)));
        assertThat(reserved(product)).isZero();
        assertThat(couponUsed("C1")).isZero();
    }

    // ---------------------------------------------------------------- R09 additions

    private static String cursorFor(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("R09 default page size is 20: 25 orders give 20 + a cursor, then 5 + null")
    void list_defaultSizeIs20() throws Exception {
        long product = api.createProduct("Desk", 100, 1000);
        for (int i = 0; i < 25; i++) {
            api.createOrder("u1", "k" + i, null, product, 1);
        }

        JsonNode page1 = api.json(api.listOrders("userId=u1").andReturn());
        assertThat(page1.get("content")).hasSize(20);
        assertThat(page1.get("nextCursor").isNull()).isFalse();
        JsonNode page2 = api.json(api.listOrders("userId=u1&cursor=" + page1.get("nextCursor").asText()).andReturn());
        assertThat(page2.get("content")).hasSize(5);
        assertThat(page2.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("R09 size 1 and size 100 (the maximum) are accepted")
    void list_sizeBounds() throws Exception {
        long product = api.createProduct("Desk", 100, 1000);
        for (int i = 0; i < 3; i++) {
            api.createOrder("u1", "k" + i, null, product, 1);
        }
        api.listOrders("size=1").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
        api.listOrders("size=100").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        api.listOrders("size=-1").andExpect(status().isBadRequest());
        api.listOrders("size=").andExpect(status().isBadRequest());
        api.listOrders("size=1.5").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R09 a result of exactly `size` rows has nextCursor null (no phantom empty last page)")
    void list_exactFitHasNoNextCursor() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        for (int i = 0; i < 4; i++) {
            api.createOrder("u1", "k" + i, null, product, 1);
        }
        api.listOrders("userId=u1&size=4").andExpect(jsonPath("$.content", hasSize(4))).andExpect(jsonPath("$.nextCursor").value(nullValue()));
        api.listOrders("userId=u1&size=3").andExpect(jsonPath("$.content", hasSize(3))).andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    @DisplayName("R09 paging is stable: orders created between two page requests do not shift or duplicate rows")
    void list_cursorIsStableWhileNewOrdersArrive() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(api.createOrder("u1", "k" + i, null, product, 1).get("id").asLong());
        }
        JsonNode page1 = api.json(api.listOrders("userId=u1&size=2").andReturn());

        api.createOrder("u1", "new1", null, product, 1);
        api.createOrder("u1", "new2", null, product, 1);

        JsonNode page2 = api.json(api.listOrders("userId=u1&size=2&cursor=" + page1.get("nextCursor").asText()).andReturn());
        assertThat(page1.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        assertThat(page1.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(3));
        assertThat(page2.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(page2.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));
    }

    @Test
    @DisplayName("R09 userId + status filters combine, and a filtered query pages through all matches without gaps")
    void list_filterCombinationsAndFilteredPaging() throws Exception {
        long product = api.createProduct("Desk", 100, 1000);
        List<Long> paidU1 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            paidU1.add(api.createPaidOrder("u1", "p" + i, null, product, 1));
            api.createOrder("u1", "n" + i, null, product, 1); // interleaved PENDING orders
        }
        api.createPaidOrder("u2", "other-paid", null, product, 1);
        api.createOrder("u2", "other-pending", null, product, 1);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = api.json(api.listOrders("userId=u1&status=PAID&size=2" + (cursor == null ? "" : "&cursor=" + cursor)).andReturn());
            page.get("content").forEach(o -> {
                assertThat(o.get("userId").asText()).isEqualTo("u1");
                assertThat(o.get("status").asText()).isEqualTo("PAID");
                seen.add(o.get("id").asLong());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);

        List<Long> expected = new ArrayList<>(paidU1);
        java.util.Collections.reverse(expected);
        assertThat(seen).containsExactlyElementsOf(expected);
        api.listOrders("status=PAID").andExpect(jsonPath("$.content", hasSize(6)));
        api.listOrders("status=PENDING_PAYMENT").andExpect(jsonPath("$.content", hasSize(6)));
        api.listOrders("userId=u2").andExpect(jsonPath("$.content", hasSize(2)));
        api.listOrders("userId=u2&status=PENDING_PAYMENT").andExpect(jsonPath("$.content", hasSize(1)));
        api.listOrders("userId=u1&status=SHIPPED").andExpect(jsonPath("$.content", hasSize(0))).andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @DisplayName("R09 every one of the 8 status names is accepted as a filter; names are case-sensitive")
    void list_acceptsEveryStatusName() throws Exception {
        for (String name : new String[] {"PENDING_PAYMENT", "PAID", "PAYMENT_FAILED", "EXPIRED", "CANCELLED", "REFUNDED", "SHIPPED", "DELIVERED"}) {
            api.listOrders("status=" + name).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        }
        api.listOrders("status=paid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"));
    }

    @Test
    @DisplayName("R09 malformed cursors are 400 invalid-cursor: unknown version, id 0, negative, text, overflow, padding, non-canonical, empty")
    void list_malformedCursors_return400InvalidCursor() throws Exception {
        String invalidCursor = "urn:problem:order-payment:invalid-cursor";
        String[] bad = {
                cursorFor("v1:0"), cursorFor("v1:-5"), cursorFor("v1:abc"), cursorFor("v1:"), cursorFor("v2:5"), cursorFor("5"),
                cursorFor("v1:99999999999999999999"), cursorFor("v1:007"), cursorFor("v1:5 "),
                java.util.Base64.getUrlEncoder().encodeToString("v1:5".getBytes()), // padded: not canonical
                "!!!", "%20", ""};
        for (String cursor : bad) {
            api.listOrders("cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(invalidCursor));
        }
    }

    @Test
    @DisplayName("R09 a syntactically valid cursor past the oldest order returns an empty page with nextCursor null")
    void list_cursorBeyondOldest_returnsEmptyPage() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        long only = api.createOrder("u1", "k1", null, product, 1).get("id").asLong();

        api.listOrders("cursor=" + com.example.order.order.Cursor.encode(only))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0))).andExpect(jsonPath("$.nextCursor").value(nullValue()));
        api.listOrders("cursor=" + com.example.order.order.Cursor.encode(only + 1))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("R09 several invalid parameters are reported together in errors[]; blank userId is 400")
    void list_multipleInvalidParameters_areAllReported() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders")
                        .param("status", "nope").param("size", "0").param("userId", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:order-payment:validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(3)));
    }

    @Test
    @DisplayName("R09 the page body is exactly {content, nextCursor} and each content element is a full order with its items")
    void list_pageShape() throws Exception {
        long product = api.createProduct("Desk", 100, 100);
        api.createOrder("u1", "k1", null, product, 2);

        JsonNode page = api.json(api.listOrders("userId=u1").andReturn());
        var names = new java.util.TreeSet<String>();
        page.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("content", "nextCursor");
        assertThat(page.get("content").get(0).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("items").get(0).get("unitPrice").asLong()).isEqualTo(100);
    }
}
