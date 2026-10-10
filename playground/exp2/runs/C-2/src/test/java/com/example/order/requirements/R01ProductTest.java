package com.example.order.requirements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.order.support.AbstractIntegrationTest;
import com.example.order.support.ApiResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R1. 상품 */
class R01ProductTest extends AbstractIntegrationTest {

    private static String product(String nameJson, String price, String stock) {
        StringBuilder sb = new StringBuilder("{");
        String sep = "";
        if (nameJson != null) {
            sb.append("\"name\":").append(nameJson);
            sep = ",";
        }
        if (price != null) {
            sb.append(sep).append("\"price\":").append(price);
            sep = ",";
        }
        if (stock != null) {
            sb.append(sep).append("\"stock\":").append(stock);
        }
        return sb.append("}").toString();
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    @Test
    @DisplayName("R1.1 상품 등록은 201 + Location을 돌려주고 본문은 {id,name,price,stock,reserved=0,available}이다")
    void r1_1_create_returnsCreatedWithLocationAndBody() {
        ApiResponse r = createProduct("키보드", 45_000, 10);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).endsWith("/api/products/" + r.id());
        assertThat(r.json().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "price", "stock", "reserved", "available");
        assertThat(r.text("name")).isEqualTo("키보드");
        assertThat(r.longValue("price")).isEqualTo(45_000);
        assertThat(r.json().get("stock").asInt()).isEqualTo(10);
        assertThat(r.json().get("reserved").asInt()).isZero();
        assertThat(r.json().get("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("R1.1 등록 응답 본문은 R1.3 조회 본문과 같은 형태·값이다")
    void r1_1_createBody_equalsGetBody() {
        ApiResponse created = createProduct("same-shape", 1_000, 3);

        ApiResponse fetched = getProduct(created.id());

        assertThat(fetched.json()).isEqualTo(created.json());
    }

    @Test
    @DisplayName("R1.1 Location이 가리키는 URL로 조회하면 200이다")
    void r1_1_locationHeader_isResolvable() {
        ApiResponse created = createProduct("loc", 1_000, 3);
        String location = created.header("Location");

        ApiResponse fetched = get(location.substring(location.indexOf("/api/")));

        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.id()).isEqualTo(created.id());
    }

    @ParameterizedTest(name = "R1.2 허용 경계 {0} -> 201")
    @MethodSource("validBoundaries")
    @DisplayName("R1.2 경계값 이내의 요청은 201이다")
    void r1_2_validBoundaries_areAccepted(String label, String body) {
        ApiResponse r = postProduct(body);

        assertThat(r.status()).as("%s: %s", label, r).isEqualTo(201);
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(
                arguments("name 1자", product(quoted("a"), "1", "0")),
                arguments("name 100자", product(quoted("a".repeat(100)), "100", "1")),
                arguments("name 한글 100자", product(quoted("가".repeat(100)), "100", "1")),
                arguments("price 1", product(quoted("p"), "1", "1")),
                arguments("price 10,000,000", product(quoted("p"), "10000000", "1")),
                arguments("stock 0", product(quoted("p"), "100", "0")),
                arguments("stock 1,000,000", product(quoted("p"), "100", "1000000")));
    }

    @ParameterizedTest(name = "R1.2 위반 {0} -> 400")
    @MethodSource("invalidBodies")
    @DisplayName("R1.2 name·price·stock 위반은 400 VALIDATION_ERROR이다")
    void r1_2_violations_return400(String label, String body) {
        ApiResponse r = postProduct(body);

        assertProblem(r, 400, "VALIDATION_ERROR");
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                arguments("name 누락", product(null, "100", "1")),
                arguments("name null", product("null", "100", "1")),
                arguments("name 빈 문자열", product(quoted(""), "100", "1")),
                arguments("name 공백만", product(quoted("   "), "100", "1")),
                arguments("name 탭·개행만", product(quoted("\\t\\n"), "100", "1")),
                arguments("name 101자", product(quoted("a".repeat(101)), "100", "1")),
                arguments("price 0", product(quoted("p"), "0", "1")),
                arguments("price -1", product(quoted("p"), "-1", "1")),
                arguments("price 10,000,001", product(quoted("p"), "10000001", "1")),
                arguments("price 누락", product(quoted("p"), null, "1")),
                arguments("stock -1", product(quoted("p"), "100", "-1")),
                arguments("stock 1,000,001", product(quoted("p"), "100", "1000001")),
                arguments("stock 누락", product(quoted("p"), "100", null)));
    }

    @Test
    @DisplayName("R1.3 상품 조회는 200 {id,name,price,stock,reserved,available}이다")
    void r1_3_get_returnsAllFields() {
        long id = newProduct(7_000, 5);

        ApiResponse r = getProduct(id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.id()).isEqualTo(id);
        assertThat(r.json().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "price", "stock", "reserved", "available");
        assertThat(r.longValue("price")).isEqualTo(7_000);
    }

    @Test
    @DisplayName("R1.3 없는 상품 조회는 404 PRODUCT_NOT_FOUND이다")
    void r1_3_get_unknown_returns404() {
        assertProblem(getProduct(987_654_321L), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("R1.3 숫자가 아닌 id는 400이다")
    void r1_3_get_nonNumericId_returns400() {
        assertProblem(get("/api/products/abc"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("R1.4 주문 생성은 reserved를 늘리고 available = stock - reserved이다")
    void r1_4_reserved_and_available_afterOrder() {
        long id = newProduct(1_000, 10);

        placeOrderOk(id, 4);

        ApiResponse r = getProduct(id);
        assertThat(r.json().get("stock").asInt()).isEqualTo(10);
        assertThat(r.json().get("reserved").asInt()).isEqualTo(4);
        assertThat(r.json().get("available").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("R1.4 결제 승인 후 stock과 reserved가 함께 줄고 available은 유지된다")
    void r1_4_stock_and_reserved_afterPayment() {
        long id = newProduct(1_000, 10);
        long orderId = placeOrderOk(id, 4).id();
        int availableBefore = available(id);

        assertThat(pay(orderId).status()).isEqualTo(200);

        ApiResponse r = getProduct(id);
        assertThat(r.json().get("stock").asInt()).isEqualTo(6);
        assertThat(r.json().get("reserved").asInt()).isZero();
        assertThat(r.json().get("available").asInt()).isEqualTo(availableBefore).isEqualTo(6);
    }

    @Test
    @DisplayName("R1.4 available이 0이면 다음 주문은 409 INSUFFICIENT_STOCK이다")
    void r1_4_availableZero_blocksFurtherOrders() {
        long id = newProduct(1_000, 3);
        placeOrderOk(id, 3);

        ApiResponse r = placeOrder(null, line(id, 1));

        assertProblem(r, 409, "INSUFFICIENT_STOCK");
        assertThat(available(id)).isZero();
    }
}
