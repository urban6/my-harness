package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.order.support.AbstractIntegrationTest;

/** R8 에러 포맷: RFC 9457, application/problem+json, type/title/status/detail. */
class ErrorFormatTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("R8: 검증 실패 400은 problem+json이고 status가 400이다")
    void r8_validationError_isProblemJson() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\":\"  \",\"price\":0,\"stock\":-1}");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 깨진 JSON 본문 400은 problem+json이다")
    void r8_brokenJson_isProblemJson() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\": \"a\", \"price\": ");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 금액에 문자열을 넣은 타입 불일치 400도 problem+json이다")
    void r8_wrongTypeJson_isProblemJson() {
        ResponseEntity<Map> res = postRaw("/api/products", "{\"name\":\"a\",\"price\":\"1000\",\"stock\":1}");

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 본문이 아예 없는 주문 생성 요청 400도 problem+json이다")
    void r8_missingBody_isProblemJson() {
        ResponseEntity<Map> res = rest.postForEntity("/api/orders", null, Map.class);

        assertProblem(res, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 경로 변수 타입 오류 400은 problem+json이다")
    void r8_pathVariableTypeMismatch_isProblemJson() {
        assertProblem(rest.getForEntity("/api/orders/abc", Map.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 페이지 파라미터 범위 오류 400은 problem+json이다")
    void r8_pageParamOutOfRange_isProblemJson() {
        assertProblem(rest.getForEntity("/api/orders?size=101", Map.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R8: 상품 없음 404는 problem+json이다")
    void r8_productNotFound_isProblemJson() {
        assertProblem(getProduct(999_999L), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R8: 주문 없음 404는 problem+json이다")
    void r8_orderNotFound_isProblemJson() {
        assertProblem(getOrder(999_999L), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R8: 주문 생성 시 없는 상품 404는 problem+json이다")
    void r8_orderWithUnknownProduct_isProblemJson() {
        assertProblem(postOrder(item(999_999L, 1)), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R8: 재고 부족 409는 problem+json이다")
    void r8_insufficientStock_isProblemJson() {
        long p = createProduct("A", 1000, 1);

        assertProblem(postOrder(item(p, 2)), HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("R8: 이미 취소된 주문 재취소 409는 problem+json이다")
    void r8_alreadyCancelled_isProblemJson() {
        long p = createProduct("A", 1000, 5);
        long orderId = idOf(postOrder(item(p, 1)));
        cancelOrder(orderId);

        assertProblem(cancelOrder(orderId), HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("R8: 클라이언트가 Accept: application/json만 보내도 에러는 application/problem+json이다")
    void r8_acceptApplicationJson_stillProblemJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> res = rest.exchange("/api/products/{id}", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class, 999_999L);

        assertProblem(res, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("R8: 검증 400의 type과 404/409의 type은 서로 구분된다")
    void r8_typesDifferPerErrorKind() {
        long p = createProduct("A", 1000, 1);
        Object validation = postRaw("/api/products", "{}").getBody().get("type");
        Object notFound = getProduct(999_999L).getBody().get("type");
        Object conflict = postOrder(item(p, 2)).getBody().get("type");

        assertThat(List.of(validation, notFound, conflict)).doesNotHaveDuplicates();
    }
}
