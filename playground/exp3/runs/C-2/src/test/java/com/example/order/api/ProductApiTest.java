package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-01/REQ-02 product API")
class ProductApiTest extends IntegrationTestBase {

    @Test
    @DisplayName("REQ-01 상품 등록 -> 201 + Location + reserved=0, available=stock")
    void createReturns201WithLocationAndProduct() {
        ResponseEntity<JsonNode> res = api.createProduct("keyboard", 30000, 10);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = res.getBody().get("id").asLong();
        assertThat(res.getHeaders().getLocation()).isNotNull();
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/products/" + id);
        assertThat(res.getBody().get("name").asText()).isEqualTo("keyboard");
        assertThat(res.getBody().get("price").asLong()).isEqualTo(30000);
        assertThat(res.getBody().get("stock").asInt()).isEqualTo(10);
        assertThat(res.getBody().get("reserved").asInt()).isZero();
        assertThat(res.getBody().get("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("REQ-01 경계값 price=0, stock=0 은 허용된다")
    void createAllowsZeroPriceAndZeroStock() {
        ResponseEntity<JsonNode> res = api.createProduct("free-sample", 0, 0);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("available").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-01 name 누락 -> 400 validation-failed (errors[].field=name)")
    void createWithoutNameIsValidationFailed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("price", 100);
        body.put("stock", 1);

        ResponseEntity<JsonNode> res = api.post("/api/products", body);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("name");
    }

    @ParameterizedTest(name = "name=[{0}]")
    @ValueSource(strings = { "", " ", "   " })
    @DisplayName("REQ-01 공백 name -> 400 validation-failed")
    void createWithBlankNameIsValidationFailed(String name) {
        ResponseEntity<JsonNode> res = api.createProduct(name, 100, 1);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("name");
    }

    @Test
    @DisplayName("REQ-01 name 201자 -> 400 validation-failed")
    void createWithTooLongNameIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.createProduct("x".repeat(201), 100, 1);

        assertProblem(res, 400, "validation-failed");
    }

    @Test
    @DisplayName("REQ-01 price<0 -> 400 validation-failed (errors[].field=price)")
    void createWithNegativePriceIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.createProduct("p", -1, 1);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("price");
    }

    @Test
    @DisplayName("REQ-01 stock<0 -> 400 validation-failed (errors[].field=stock)")
    void createWithNegativeStockIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.createProduct("p", 100, -1);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("stock");
    }

    @Test
    @DisplayName("REQ-01 price 가 상한(1e9)을 넘으면 400 validation-failed")
    void createWithPriceAboveMaxIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.createProduct("p", 1_000_000_001L, 1);

        assertProblem(res, 400, "validation-failed");
    }

    @Test
    @DisplayName("REQ-01 price 누락 -> 400 validation-failed")
    void createWithoutPriceIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.post("/api/products", Map.of("name", "p", "stock", 1));

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("price");
    }

    @Test
    @DisplayName("REQ-01/16 소수 가격(10.5)은 절삭되지 않고 400 malformed-request")
    void createWithDecimalPriceIsRejected() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/products",
                "{\"name\":\"p\",\"price\":10.5,\"stock\":1}", MediaType.APPLICATION_JSON);

        assertProblem(res, 400, "malformed-request");
    }

    @Test
    @DisplayName("REQ-01 JSON 파싱 오류 -> 400 malformed-request")
    void createWithBrokenJsonIsMalformed() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/products", "{not json",
                MediaType.APPLICATION_JSON);

        assertProblem(res, 400, "malformed-request");
    }

    @Test
    @DisplayName("REQ-01 price 가 문자열이면 400 malformed-request")
    void createWithWrongTypeIsMalformed() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/products",
                "{\"name\":\"p\",\"price\":\"abc\",\"stock\":1}", MediaType.APPLICATION_JSON);

        assertProblem(res, 400, "malformed-request");
    }

    @Test
    @DisplayName("REQ-02 조회 -> {id,name,price,stock,reserved,available}, available = stock - reserved")
    void getReturnsAvailableAsStockMinusReserved() {
        long productId = api.newProduct(1000, 10);
        api.orderOk(productId, 4);

        JsonNode product = api.product(productId);

        assertThat(product.get("stock").asInt()).isEqualTo(10);
        assertThat(product.get("reserved").asInt()).isEqualTo(4);
        assertThat(product.get("available").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("REQ-02 없는 상품 -> 404 product-not-found (productId 확장)")
    void getUnknownProductIsNotFound() {
        ResponseEntity<JsonNode> res = api.get("/api/products/987654321");

        assertProblem(res, 404, "product-not-found");
        assertThat(res.getBody().get("productId").asLong()).isEqualTo(987654321L);
    }

    @Test
    @DisplayName("REQ-02/15 [verifier 관찰 2] 경로 id 가 숫자가 아니면 400 invalid-parameter, parameter='id'")
    void getWithNonNumericIdIsInvalidParameterNamingTheParameter() {
        ResponseEntity<JsonNode> res = api.get("/api/products/abc");

        assertProblem(res, 400, "invalid-parameter");
        assertThat(res.getBody().get("parameter").asText()).isEqualTo("id");
    }
}
