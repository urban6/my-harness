package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** R8 에러 포맷: RFC 9457 Problem Details, application/problem+json, type/title/status/detail. */
class ProblemDetailsApiTest extends AbstractApiTest {

    @Test
    @DisplayName("R8 깨진 JSON 이면 400 malformed-request + problem+json")
    void brokenJson_returns400Malformed() {
        assertProblem(postRaw("/api/products", "{\"name\": \"x\", "), 400, "malformed-request");
        assertProblem(postRaw("/api/orders", "not json at all"), 400, "malformed-request");
    }

    @Test
    @DisplayName("R8 본문이 비어 있는 POST 도 400 malformed-request")
    void emptyBody_returns400Malformed() {
        assertProblem(postNoBody("/api/products"), 400, "malformed-request");
        assertProblem(postNoBody("/api/orders"), 400, "malformed-request");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"name\":\"a\",\"price\":1.5,\"stock\":1}",
        "{\"name\":\"a\",\"price\":\"10\",\"stock\":1}",
        "{\"name\":\"a\",\"price\":100,\"stock\":\"3\"}",
        "{\"name\":\"a\",\"price\":100,\"stock\":2.5}",
        "{\"name\":\"a\",\"price\":true,\"stock\":1}"
    })
    @DisplayName("R8 Jackson 엄격 모드: price/stock 이 정수가 아니면 400 malformed-request")
    void strictJackson_rejectsNonIntegerNumbers(String json) {
        ResponseEntity<JsonNode> res = postRaw("/api/products", json);

        assertProblem(res, 400, "malformed-request");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
    }

    @Test
    @DisplayName("R8 주문 본문의 quantity/productId 가 정수가 아니면 400 malformed-request, 재고 불변")
    void strictJackson_rejectsNonIntegerOrderFields() {
        long p = createProduct("A", 1000, 5);

        ResponseEntity<JsonNode> quantity = postRaw("/api/orders",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":\"abc\"}]}");
        ResponseEntity<JsonNode> fractional = postRaw("/api/orders",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":1.5}]}");
        ResponseEntity<JsonNode> notArray = postRaw("/api/orders", "{\"items\":\"x\"}");

        assertProblem(quantity, 400, "malformed-request");
        assertProblem(fractional, 400, "malformed-request");
        assertProblem(notArray, 400, "malformed-request");
        assertThat(stockOf(p)).isEqualTo(5);
    }

    @Test
    @DisplayName("R8 JSON 파싱 실패의 detail 은 비어 있지 않다")
    void malformedProblem_hasNonBlankDetail() {
        JsonNode body = postRaw("/api/products", "{oops").getBody();

        assertThat(body.get("type").asText()).isNotBlank();
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("detail").asText()).isNotBlank();
    }

    @Test
    @DisplayName("R8 500 이 아닌 계약 에러 응답은 본문에 내부 정보(예외 클래스/SQL/스택)를 노출하지 않는다")
    void errorBody_doesNotLeakInternals() {
        long p = createProduct("A", 1000, 1);
        List<ResponseEntity<JsonNode>> responses = List.of(
                get("/api/products/999"), createOrder(List.of(item(p, 9))), postRaw("/api/products", "{oops"));

        for (ResponseEntity<JsonNode> r : responses) {
            String text = r.getBody().toString();
            assertThat(text).doesNotContain("Exception", "org.springframework", "com.fasterxml", "SELECT ", "at com.");
        }
    }

    /** Accept 헤더 협상 케이스: 모든 에러 종류에 대해 Accept 가 application/json 이어도 problem+json 이어야 한다. */
    static Stream<Arguments> errorRequests() {
        return Stream.of(
                Arguments.of("400 validation (POST /api/products)", HttpMethod.POST, "/api/products",
                        "{\"name\":\"\",\"price\":0,\"stock\":-1}", 400, "validation-error"),
                Arguments.of("400 malformed (POST /api/products)", HttpMethod.POST, "/api/products",
                        "{broken", 400, "malformed-request"),
                Arguments.of("400 type mismatch (GET /api/products/abc)", HttpMethod.GET, "/api/products/abc",
                        null, 400, "validation-error"),
                Arguments.of("400 page range (GET /api/orders?size=101)", HttpMethod.GET, "/api/orders?size=101",
                        null, 400, "validation-error"),
                Arguments.of("404 product", HttpMethod.GET, "/api/products/999", null, 404, "product-not-found"),
                Arguments.of("404 order", HttpMethod.GET, "/api/orders/999", null, 404, "order-not-found"),
                Arguments.of("404 cancel", HttpMethod.POST, "/api/orders/999/cancel", null, 404,
                        "order-not-found"),
                Arguments.of("404 order create unknown product", HttpMethod.POST, "/api/orders",
                        "{\"items\":[{\"productId\":999,\"quantity\":1}]}", 404, "product-not-found"));
    }

    @ParameterizedTest(name = "Accept: application/json -> {0}")
    @MethodSource("errorRequests")
    @DisplayName("R8 Accept: application/json 만 보내도 에러 응답은 application/problem+json")
    void acceptApplicationJson_stillReturnsProblemJson(
            String label, HttpMethod method, String path, String body, int status, String slug) {
        HttpHeaders headers = jsonHeaders(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> res = exchange(method, path, body, headers);

        assertProblem(res, status, slug);
        assertThat(res.getHeaders().getContentType().getSubtype()).isEqualTo("problem+json");
    }

    @Test
    @DisplayName("R8 Accept: application/json 로 재고 부족(409)/재취소(409)도 problem+json")
    void acceptApplicationJson_conflicts() {
        long p = createProduct("A", 1000, 1);
        HttpHeaders headers = jsonHeaders(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> insufficient = exchange(HttpMethod.POST, "/api/orders",
                "{\"items\":[{\"productId\":" + p + ",\"quantity\":2}]}", headers);
        long orderId = createOrder(List.of(item(p, 1))).getBody().get("id").asLong();
        postNoBody("/api/orders/" + orderId + "/cancel");
        ResponseEntity<JsonNode> recancel =
                exchange(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", null, headers);

        assertProblem(insufficient, 409, "insufficient-stock");
        assertProblem(recancel, 409, "order-already-cancelled");
    }

    @Test
    @DisplayName("R8 Accept 헤더가 없어도 에러 응답은 problem+json")
    void noAcceptHeader_returnsProblemJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> res = exchange(HttpMethod.POST, "/api/products", "{x", headers);

        assertProblem(res, 400, "malformed-request");
    }

    @Test
    @DisplayName("R8 모든 계약 에러(404/409 포함)가 type/title/status/detail 4개 필드를 채운다")
    void contractErrors_haveAllRequiredFields() {
        long p = createProduct("A", 1000, 1);
        long orderId = createOrder(List.of(item(p, 1))).getBody().get("id").asLong();
        postNoBody("/api/orders/" + orderId + "/cancel");

        List<ResponseEntity<JsonNode>> responses = List.of(
                post("/api/products", Map.of("name", "", "price", 0, "stock", -1)),
                get("/api/products/999"),
                createOrder(List.of(item(p, 100))),
                createOrder(List.of(item(999, 1))),
                get("/api/orders/999"),
                postNoBody("/api/orders/" + orderId + "/cancel"),
                get("/api/orders?page=-1"));

        for (ResponseEntity<JsonNode> r : responses) {
            assertThat(r.getHeaders().getContentType().toString()).startsWith(PROBLEM_JSON);
            JsonNode b = r.getBody();
            assertThat(b.hasNonNull("type")).isTrue();
            assertThat(b.get("type").asText()).isNotEqualTo("about:blank");
            assertThat(b.hasNonNull("title")).isTrue();
            assertThat(b.get("status").asInt()).isEqualTo(r.getStatusCode().value());
            assertThat(b.hasNonNull("detail")).isTrue();
        }
    }
}
