package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-03/REQ-04 coupon API")
class CouponApiTest extends IntegrationTestBase {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String UNTIL = "2026-12-31T00:00:00Z";

    private ResponseEntity<JsonNode> create(String code, String type, long value, Long min, Long max, long total,
            String from, String until) {
        return api.post("/api/coupons", api.couponBody(code, type, value, min, max, total, from, until));
    }

    @Test
    @DisplayName("REQ-03 쿠폰 등록 -> 201 + Location(/api/coupons/{code}) + usedCount=0")
    void createReturns201WithLocation() {
        String code = ApiClient.uniq("WELCOME");

        ResponseEntity<JsonNode> res = create(code, "RATE", 10, 10000L, 5000L, 100, FROM, UNTIL);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/coupons/" + code);
        JsonNode body = res.getBody();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).isEqualTo("RATE");
        assertThat(body.get("value").asLong()).isEqualTo(10);
        assertThat(body.get("minOrderAmount").asLong()).isEqualTo(10000);
        assertThat(body.get("maxDiscountAmount").asLong()).isEqualTo(5000);
        assertThat(body.get("totalQuantity").asInt()).isEqualTo(100);
        assertThat(body.get("usedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("REQ-03 minOrderAmount/maxDiscountAmount 생략 -> min=0, max=null 로 응답")
    void createWithoutOptionalFieldsDefaultsMinToZeroAndMaxToNull() {
        String code = ApiClient.uniq("OPT");

        ResponseEntity<JsonNode> res = create(code, "FIXED", 1000, null, null, 1, FROM, UNTIL);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("minOrderAmount").asLong()).isZero();
        assertThat(res.getBody().get("maxDiscountAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("REQ-03 같은 code 재등록 -> 409 coupon-code-duplicated (couponCode 확장)")
    void createDuplicateCodeIsConflict() {
        String code = ApiClient.uniq("DUP");
        create(code, "FIXED", 1000, null, null, 1, FROM, UNTIL);

        ResponseEntity<JsonNode> res = create(code, "RATE", 5, null, null, 9, FROM, UNTIL);

        assertProblem(res, 409, "coupon-code-duplicated");
        assertThat(res.getBody().get("couponCode").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("REQ-03 code 는 대소문자를 구분한다 (abc 와 ABC 는 서로 다른 쿠폰)")
    void codeIsCaseSensitive() {
        String lower = ApiClient.uniq("case").toLowerCase();

        ResponseEntity<JsonNode> first = create(lower, "FIXED", 1000, null, null, 1, FROM, UNTIL);
        ResponseEntity<JsonNode> second = create(lower.toUpperCase(), "FIXED", 1000, null, null, 1, FROM, UNTIL);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("REQ-03 [REQ-17] 같은 code 를 동시에 등록하면 정확히 1건만 201, 나머지는 409")
    void concurrentDuplicateCreateYieldsSingleWinner() throws Exception {
        String code = ApiClient.uniq("RACE");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return create(code, "FIXED", 1000, null, null, 1, FROM, UNTIL).getStatusCode().value();
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflicts++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("REQ-03 RATE value 101 -> 400 validation-failed (field=value)")
    void rateAbove100IsValidationFailed() {
        ResponseEntity<JsonNode> res = create(ApiClient.uniq("R"), "RATE", 101, null, null, 1, FROM, UNTIL);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("value");
    }

    @Test
    @DisplayName("REQ-03 RATE value 100 은 허용(경계), value 0 은 400")
    void rateBoundaries() {
        ResponseEntity<JsonNode> ok = create(ApiClient.uniq("R"), "RATE", 100, null, null, 1, FROM, UNTIL);
        ResponseEntity<JsonNode> zero = create(ApiClient.uniq("R"), "RATE", 0, null, null, 1, FROM, UNTIL);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(zero, 400, "validation-failed");
    }

    @Test
    @DisplayName("REQ-03 validFrom >= validUntil -> 400 validation-failed (field=validUntil)")
    void inverseValidityPeriodIsValidationFailed() {
        ResponseEntity<JsonNode> res = create(ApiClient.uniq("P"), "FIXED", 1000, null, null, 1, UNTIL, FROM);

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("validUntil");
    }

    @Test
    @DisplayName("REQ-03 validFrom == validUntil -> 400 validation-failed")
    void equalValidityBoundsIsValidationFailed() {
        ResponseEntity<JsonNode> res = create(ApiClient.uniq("P"), "FIXED", 1000, null, null, 1, FROM, FROM);

        assertProblem(res, 400, "validation-failed");
    }

    @Test
    @DisplayName("REQ-03 알 수 없는 type -> 400 malformed-request")
    void unknownTypeIsMalformed() {
        ResponseEntity<JsonNode> res = create(ApiClient.uniq("T"), "BOGUS", 10, null, null, 1, FROM, UNTIL);

        assertProblem(res, 400, "malformed-request");
    }

    @Test
    @DisplayName("REQ-03 오프셋 없는 validFrom -> 400")
    void validFromWithoutOffsetIsRejected() {
        ResponseEntity<JsonNode> res = create(ApiClient.uniq("T"), "FIXED", 10, null, null, 1,
                "2026-01-01T00:00:00", UNTIL);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    }

    @Test
    @DisplayName("REQ-03 totalQuantity=0 / min<0 / max=0 / code 공백문자 -> 400 validation-failed")
    void invalidNumbersAndCodeAreValidationFailed() {
        assertProblem(create(ApiClient.uniq("Q"), "FIXED", 10, null, null, 0, FROM, UNTIL), 400, "validation-failed");
        assertProblem(create(ApiClient.uniq("Q"), "FIXED", 10, -1L, null, 1, FROM, UNTIL), 400, "validation-failed");
        assertProblem(create(ApiClient.uniq("Q"), "FIXED", 10, null, 0L, 1, FROM, UNTIL), 400, "validation-failed");
        assertProblem(create("has space", "FIXED", 10, null, null, 1, FROM, UNTIL), 400, "validation-failed");
    }

    @Test
    @DisplayName("REQ-03 code 누락 -> 400 validation-failed (field=code)")
    void missingCodeIsValidationFailed() {
        ResponseEntity<JsonNode> res = api.post("/api/coupons", Map.of("type", "FIXED", "value", 10, "totalQuantity", 1,
                "validFrom", FROM, "validUntil", UNTIL));

        assertProblem(res, 400, "validation-failed");
        assertThat(ApiClient.fieldsOf(res.getBody())).contains("code");
    }

    @Test
    @DisplayName("REQ-04 쿠폰 조회 -> 요청 필드 + usedCount (주문 사용 후 증가 반영)")
    void getReturnsRequestFieldsAndUsedCount() {
        String code = api.newCoupon("FIXED", 1000, null, null, 5);
        long productId = api.newProduct(5000, 10);
        api.orderOk(ApiClient.uniq("u"), productId, 1, code);

        JsonNode coupon = api.coupon(code);

        assertThat(coupon.get("code").asText()).isEqualTo(code);
        assertThat(coupon.get("type").asText()).isEqualTo("FIXED");
        assertThat(coupon.get("value").asLong()).isEqualTo(1000);
        assertThat(coupon.get("totalQuantity").asInt()).isEqualTo(5);
        assertThat(coupon.get("usedCount").asInt()).isEqualTo(1);
        assertThat(coupon.has("validFrom")).isTrue();
        assertThat(coupon.has("validUntil")).isTrue();
    }

    @Test
    @DisplayName("REQ-04 없는 쿠폰 -> 404 coupon-not-found (couponCode 확장)")
    void getUnknownCouponIsNotFound() {
        ResponseEntity<JsonNode> res = api.get("/api/coupons/NOPE-" + System.nanoTime());

        assertProblem(res, 404, "coupon-not-found");
        assertThat(res.getBody().get("couponCode").asText()).startsWith("NOPE-");
    }

    @Test
    @DisplayName("REQ-03/15 JSON 파싱 오류 본문 -> 400 malformed-request")
    void brokenJsonIsMalformed() {
        ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/coupons", "{", MediaType.APPLICATION_JSON);

        assertProblem(res, 400, "malformed-request");
    }
}
