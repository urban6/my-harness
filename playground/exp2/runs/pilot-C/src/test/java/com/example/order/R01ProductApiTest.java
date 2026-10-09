package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("R1 상품")
class R01ProductApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R1.1 상품 등록 -> 201, Location, reserved=0, available=stock")
    void create_returns201WithLocationAndInitialValues() {
        ApiResponse r = createProduct("키보드", 12_000, 30);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/products/" + r.id());
        assertThat(r.json("name").asText()).isEqualTo("키보드");
        assertThat(r.json("price").asLong()).isEqualTo(12_000);
        assertThat(r.json("stock").asInt()).isEqualTo(30);
        assertThat(r.json("reserved").asInt()).isZero();
        assertThat(r.json("available").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("R1.1 등록 응답 본문은 R1.3 조회 본문과 같다")
    void create_bodyEqualsGetBody() {
        ApiResponse created = createProduct("마우스", 5_000, 3);

        ApiResponse fetched = get(created.header("Location"));

        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
    }

    @Test
    @DisplayName("R1.1 stock 0 상품도 등록되고 available=0")
    void create_zeroStock_availableZero() {
        ApiResponse r = createProduct("품절", 100, 0);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("available").asInt()).isZero();
    }

    @ParameterizedTest(name = "R1.2 경계값 허용: price={0}, stock={1}")
    @CsvSource({"1,0", "1,1", "10000000,1000000", "9999999,999999"})
    void create_boundaryValuesAccepted(long price, int stock) {
        ApiResponse r = createProduct("경계", price, stock);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json("price").asLong()).isEqualTo(price);
        assertThat(r.json("stock").asInt()).isEqualTo(stock);
    }

    @ParameterizedTest(name = "R1.2 경계값 위반 400: price={0}, stock={1}")
    @CsvSource({"0,10", "-1,10", "10000001,10", "100,-1", "100,1000001"})
    void create_boundaryViolations400(long price, int stock) {
        ApiResponse r = createProduct("경계", price, stock);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 name 100자는 허용")
    void create_name100Chars_ok() {
        assertThat(createProduct("a".repeat(100), 100, 1).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("R1.2 name 101자는 400")
    void create_name101Chars_400() {
        assertThat(createProduct("a".repeat(101), 100, 1).status()).isEqualTo(400);
    }

    @ParameterizedTest(name = "R1.2 공백 name [{0}] -> 400")
    @CsvSource(value = {"''", "' '", "'     '"})
    void create_blankName_400(String name) {
        assertThat(createProduct(name, 100, 1).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.2 name 누락 -> 400")
    void create_missingName_400() {
        ApiResponse r = post("/api/products", obj().put("price", 100).put("stock", 1), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.2 price 누락 -> 400")
    void create_missingPrice_400() {
        ApiResponse r = post("/api/products", obj().put("name", "x").put("stock", 1), null);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.2 stock 누락 -> 400")
    void create_missingStock_400() {
        ApiResponse r = post("/api/products", obj().put("name", "x").put("price", 1), null);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.2 name 이 null -> 400")
    void create_nullName_400() {
        ApiResponse r = post("/api/products", "{\"name\":null,\"price\":100,\"stock\":1}", null);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("R1.3 상품 조회 -> 200 {id,name,price,stock,reserved,available}")
    void get_returnsAllFields() {
        long id = newProduct(777, 9);

        ApiResponse r = getProduct(id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().fieldNames()).toIterable()
                .contains("id", "name", "price", "stock", "reserved", "available");
        assertThat(r.id()).isEqualTo(id);
        assertThat(r.json("available").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("R1.3 없는 상품 -> 404 PRODUCT_NOT_FOUND")
    void get_unknown_404() {
        ApiResponse r = getProduct(Long.MAX_VALUE - 1);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.3 숫자가 아닌 id -> 400")
    void get_nonNumericId_400() {
        ApiResponse r = get("/api/products/abc");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.4 주문 생성 시 reserved 증가, available = stock - reserved, stock 은 그대로")
    void availableEqualsStockMinusReserved() {
        long p = newProduct(1_000, 10);

        newOrder(uniqueUser(), null, p, 3);

        ApiResponse r = getProduct(p);
        assertThat(r.json("stock").asInt()).isEqualTo(10);
        assertThat(r.json("reserved").asInt()).isEqualTo(3);
        assertThat(r.json("available").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("R1.4 결제 승인 후 stock 과 reserved 가 함께 감소해 available 은 유지")
    void afterPayment_stockAndReservedDecrease() {
        long p = newProduct(1_000, 10);
        newPaidOrder(uniqueUser(), null, p, 4);

        ApiResponse r = getProduct(p);
        assertThat(r.json("stock").asInt()).isEqualTo(6);
        assertThat(r.json("reserved").asInt()).isZero();
        assertThat(r.json("available").asInt()).isEqualTo(6);
    }
}
