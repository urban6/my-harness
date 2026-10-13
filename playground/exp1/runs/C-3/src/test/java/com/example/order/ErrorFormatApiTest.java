package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** R8(에러 포맷): 400/404/409 및 JSON 파싱 실패가 RFC 9457 application/problem+json 인지 검증한다. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ErrorFormatApiTest extends AbstractIntegrationTest {

    private static final String TYPE_PREFIX = "https://example.com/problems/";

    private void assertType(ResponseEntity<Map> res, String suffix) {
        assertThat(res.getBody().get("type")).isEqualTo(TYPE_PREFIX + suffix);
    }

    @Test
    @DisplayName("R8: 400 검증 실패(상품 등록) - problem+json, 필수 필드, status 일치, errors 확장 필드")
    void r8_validationError_isProblemJson() {
        ResponseEntity<Map> res = post("/api/products", Map.of("name", "", "price", 0, "stock", -1));

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertType(res, "validation-error");
        assertThat((List<Map>) res.getBody().get("errors")).isNotEmpty();
    }

    @Test
    @DisplayName("R8: 400 검증 실패(주문 items 비어있음) - problem+json")
    void r8_validationError_orderItemsEmpty_isProblemJson() {
        assertProblem(post("/api/orders", Map.of("items", List.of())), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 400 깨진 JSON - problem+json (malformed-request)")
    void r8_malformedJson_isProblemJson() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\": \"a\", \"price\": ");

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertType(res, "malformed-request");
    }

    @Test
    @DisplayName("R8: 400 깨진 JSON(주문 생성) - problem+json")
    void r8_malformedJson_orders_isProblemJson() {
        assertProblem(postRaw("/api/orders", "{not json"), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 400 빈 본문 - problem+json")
    void r8_emptyBody_isProblemJson() {
        assertProblem(postRaw("/api/products", ""), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 400 타입 불일치(price가 문자열) - problem+json")
    void r8_wrongFieldType_isProblemJson() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\":\"a\",\"price\":\"abc\",\"stock\":1}");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 400 쿼리 파라미터 범위 밖 - problem+json (validation-error)")
    void r8_queryOutOfRange_isProblemJson() {
        ResponseEntity<Map> res = get("/api/orders?size=101");

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertType(res, "validation-error");
    }

    @Test
    @DisplayName("R8: 400 쿼리 파라미터 타입 불일치 - problem+json (invalid-parameter)")
    void r8_queryTypeMismatch_isProblemJson() {
        ResponseEntity<Map> res = get("/api/orders?page=abc");

        assertProblem(res, HttpStatus.BAD_REQUEST);
        assertType(res, "invalid-parameter");
    }

    @Test
    @DisplayName("R8: 404 상품 없음 - problem+json (product-not-found)")
    void r8_productNotFound_isProblemJson() {
        ResponseEntity<Map> res = get("/api/products/999999");

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertType(res, "product-not-found");
    }

    @Test
    @DisplayName("R8: 404 주문 없음(조회/취소) - problem+json (order-not-found)")
    void r8_orderNotFound_isProblemJson() {
        ResponseEntity<Map> got = get("/api/orders/999999");
        ResponseEntity<Map> cancelled = post("/api/orders/999999/cancel", null);

        assertProblem(got, HttpStatus.NOT_FOUND);
        assertType(got, "order-not-found");
        assertProblem(cancelled, HttpStatus.NOT_FOUND);
        assertType(cancelled, "order-not-found");
    }

    @Test
    @DisplayName("R8: 404 주문 생성 시 없는 상품 - problem+json (product-not-found)")
    void r8_orderWithUnknownProduct_isProblemJson() {
        ResponseEntity<Map> res = placeOrder(item(999999, 1));

        assertProblem(res, HttpStatus.NOT_FOUND);
        assertType(res, "product-not-found");
    }

    @Test
    @DisplayName("R8: 409 재고 부족 - problem+json (insufficient-stock)")
    void r8_insufficientStock_isProblemJson() {
        long a = createProduct("A", 1000, 1);

        ResponseEntity<Map> res = placeOrder(item(a, 2));

        assertProblem(res, HttpStatus.CONFLICT);
        assertType(res, "insufficient-stock");
    }

    @Test
    @DisplayName("R8: 409 이미 취소된 주문 - problem+json (order-already-cancelled)")
    void r8_alreadyCancelled_isProblemJson() {
        long a = createProduct("A", 1000, 1);
        Map<String, Object> created = createOrder(item(a, 1));
        String url = "/api/orders/" + num(created, "id") + "/cancel";
        post(url, null);

        ResponseEntity<Map> res = post(url, null);

        assertProblem(res, HttpStatus.CONFLICT);
        assertType(res, "order-already-cancelled");
    }

    @Test
    @DisplayName("R8: 에러 응답은 내부 정보(스택트레이스/예외 클래스명)를 노출하지 않는다")
    void r8_errorBody_doesNotLeakInternals() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\": ");

        String text = new HashMap<>(res.getBody()).toString();
        assertThat(text).doesNotContain("Exception").doesNotContain("com.fasterxml").doesNotContain("at com.");
    }
}
