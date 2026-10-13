package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** happy path 스모크: R1 상품 등록 / R3 주문 생성(재고 차감) / R4 조회. 전체 R1~R8 커버리지는 test-writer가 확장한다. */
class OrderFlowSmokeTest extends AbstractIntegrationTest {

    @Test
    void createProduct_returns201_withLocationAndBody() {
        ResponseEntity<Map> res = rest.postForEntity("/api/products",
                Map.of("name", "키보드", "price", 35000, "stock", 10), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number id = (Number) res.getBody().get("id");
        assertThat(res.getHeaders().getLocation().toString()).endsWith("/api/products/" + id);
        assertThat(res.getBody()).containsEntry("name", "키보드")
                .containsEntry("price", 35000).containsEntry("stock", 10);
    }

    @Test
    void createOrder_returns201_decreasesStock_andIsRetrievable() {
        long productId = createProduct("마우스", 20000, 5);

        ResponseEntity<Map> created = postOrder(item(productId, 2));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number orderId = (Number) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation().toString()).endsWith("/api/orders/" + orderId);
        assertThat(created.getBody()).containsEntry("status", "ORDERED").containsEntry("totalPrice", 40000);
        assertThat(getProduct(productId).getBody()).containsEntry("stock", 3);

        ResponseEntity<Map> fetched = rest.getForEntity("/api/orders/{id}", Map.class, orderId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    @Test
    void getUnknownProduct_returns404_asProblemJson() {
        ResponseEntity<Map> res = rest.getForEntity("/api/products/{id}", Map.class, 999999);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(res.getBody()).containsKeys("type", "title", "status", "detail");
    }
}
