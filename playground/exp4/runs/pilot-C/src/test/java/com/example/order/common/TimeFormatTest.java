package com.example.order.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C2. 시각 필드는 오프셋(Z 또는 ±hh:mm)이 포함된 ISO-8601 문자열이다. */
class TimeFormatTest extends IntegrationTestBase {

    private static final Pattern ISO_WITH_OFFSET = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$");

    private static void assertIsoWithOffset(JsonNode node, String field) {
        assertThat(node.get(field).isTextual()).as("%s must be a JSON string in %s", field, node).isTrue();
        String text = node.get(field).asText();
        assertThat(text).as(field).matches(ISO_WITH_OFFSET);
        OffsetDateTime.parse(text); // 파싱 가능해야 한다
    }

    @Test
    @DisplayName("C2 주문의 createdAt·expiresAt 은 오프셋 포함 ISO-8601 이고 paidAt 은 null")
    void orderTimesBeforePayment() {
        JsonNode order = newOrder(newProduct(1000, 5), 1);

        assertIsoWithOffset(order, "createdAt");
        assertIsoWithOffset(order, "expiresAt");
        assertThat(order.get("paidAt").isNull()).isTrue();
        assertThat(time(order, "expiresAt")).isAfter(time(order, "createdAt"));
    }

    @Test
    @DisplayName("C2 결제 후 paidAt 도 오프셋 포함 ISO-8601 이다 (결제·조회·목록 응답 모두)")
    void paidAtFormat() {
        String user = uniqueUser();
        JsonNode created = newOrder(user, null, items(newProduct(1000, 5), 1));
        JsonNode paid = payOk(created.get("id").asLong());

        assertIsoWithOffset(paid, "paidAt");
        assertIsoWithOffset(order(created.get("id").asLong()), "paidAt");
        JsonNode listed = get("/api/orders?userId=" + user).getBody().get("content").get(0);
        assertIsoWithOffset(listed, "createdAt");
        assertIsoWithOffset(listed, "expiresAt");
        assertIsoWithOffset(listed, "paidAt");
    }

    @Test
    @DisplayName("C2 쿠폰 validFrom·validUntil 응답도 오프셋 포함 ISO-8601 이고 등록·조회 모두 같다")
    void couponTimes() {
        String code = uniqueCode();
        JsonNode created = createCoupon(couponBody(code, "FIXED", 100));

        assertIsoWithOffset(created, "validFrom");
        assertIsoWithOffset(created, "validUntil");
        assertIsoWithOffset(coupon(code), "validFrom");
        assertIsoWithOffset(coupon(code), "validUntil");
    }

    @Test
    @DisplayName("C2 오프셋이 붙은 입력은 같은 순간으로 보존된다 (+09:00 입력 -> 응답에서 같은 Instant)")
    void offsetInputKeepsInstant() {
        var body = couponBody(uniqueCode(), "FIXED", 100);
        body.put("validFrom", "2035-06-01T09:00:00.123456+09:00");
        body.put("validUntil", "2035-06-02T00:00:00-05:00");

        JsonNode created = createCoupon(body);

        assertThat(time(created, "validFrom").toInstant()).isEqualTo(OffsetDateTime.parse("2035-06-01T00:00:00.123456Z").toInstant());
        assertThat(time(created, "validUntil").toInstant()).isEqualTo(OffsetDateTime.parse("2035-06-02T05:00:00Z").toInstant());
    }
}
