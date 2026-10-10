package com.example.order.api;

import static com.example.order.api.ProblemAssertions.assertProblem;
import static com.example.order.support.ApiClient.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.support.ApiClient;
import com.example.order.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("REQ-05~08 order creation, coupon, idempotency, get")
class OrderCreateTest extends IntegrationTestBase {

    private String user() {
        return ApiClient.uniq("user");
    }

    private String key() {
        return ApiClient.uniq("key");
    }

    @Nested
    @DisplayName("REQ-05 주문 생성 + 재고 예약")
    class Create {

        @Test
        @DisplayName("REQ-05 정상 주문 -> 201 + Location + PENDING_PAYMENT, 단가 스냅샷/subtotal/예약 증가")
        void createsPendingOrderAndReservesStock() {
            long p1 = api.newProduct(30000, 10);
            long p2 = api.newProduct(500, 10);
            String user = user();

            ResponseEntity<JsonNode> res = api.placeOrder(user, key(), null, item(p1, 2), item(p2, 3));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode o = res.getBody();
            assertThat(res.getHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + o.get("id").asLong());
            assertThat(res.getHeaders().getFirst("Idempotent-Replayed")).isNull();
            assertThat(o.get("userId").asText()).isEqualTo(user);
            assertThat(o.get("status").asText()).isEqualTo("PENDING_PAYMENT");
            assertThat(o.get("items")).hasSize(2);
            assertThat(o.get("items").get(0).get("productId").asLong()).isEqualTo(p1);
            assertThat(o.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
            assertThat(o.get("items").get(0).get("unitPrice").asLong()).isEqualTo(30000);
            assertThat(o.get("items").get(1).get("unitPrice").asLong()).isEqualTo(500);
            assertThat(o.get("subtotal").asLong()).isEqualTo(61500);
            assertThat(o.get("discount").asLong()).isZero();
            assertThat(o.get("totalPrice").asLong()).isEqualTo(61500);
            assertThat(o.has("couponCode")).isTrue();
            assertThat(o.get("couponCode").isNull()).isTrue();
            assertThat(o.has("paidAt")).isTrue();
            assertThat(o.get("paidAt").isNull()).isTrue();
            assertThat(api.product(p1).get("reserved").asInt()).isEqualTo(2);
            assertThat(api.product(p1).get("stock").asInt()).isEqualTo(10);
            assertThat(api.product(p2).get("reserved").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("REQ-05 응답 items 는 요청 순서를 유지한다 (productId 정렬 아님)")
        void itemsKeepRequestOrder() {
            long low = api.newProduct(100, 10);
            long high = api.newProduct(200, 10);

            JsonNode o = api.placeOrder(user(), key(), null, item(high, 1), item(low, 1)).getBody();

            assertThat(o.get("items").get(0).get("productId").asLong()).isEqualTo(high);
            assertThat(o.get("items").get(1).get("productId").asLong()).isEqualTo(low);
        }

        @Test
        @DisplayName("REQ-05 없는 상품 -> 404 product-not-found, 앞선 항목 예약도 남지 않는다")
        void unknownProductIs404AndLeavesNoReservation() {
            long ok = api.newProduct(100, 10);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), null, item(ok, 2), item(987654321L, 1));

            assertProblem(res, 404, "product-not-found");
            assertThat(res.getBody().get("productId").asLong()).isEqualTo(987654321L);
            assertThat(api.product(ok).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-05 가용 재고 부족 -> 409 insufficient-stock (productId/requested/available)")
        void insufficientStockIsConflict() {
            long p = api.newProduct(100, 3);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), null, item(p, 4));

            assertProblem(res, 409, "insufficient-stock");
            assertThat(res.getBody().get("productId").asLong()).isEqualTo(p);
            assertThat(res.getBody().get("requested").asInt()).isEqualTo(4);
            assertThat(res.getBody().get("available").asInt()).isEqualTo(3);
            assertThat(api.product(p).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-05 경계: 가용 재고와 같은 수량은 성공하고, 이후 1개 주문은 available=0 으로 409")
        void orderingExactlyAvailableSucceedsThenNextFails() {
            long p = api.newProduct(100, 5);

            ResponseEntity<JsonNode> all = api.placeOrder(user(), key(), null, item(p, 5));
            ResponseEntity<JsonNode> next = api.placeOrder(user(), key(), null, item(p, 1));

            assertThat(all.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(api.product(p).get("available").asInt()).isZero();
            assertProblem(next, 409, "insufficient-stock");
            assertThat(next.getBody().get("available").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-05 다중 항목 중 하나가 재고 부족이면 전체 롤백 (다른 항목 예약 없음, 쿠폰 사용 없음)")
        void multiItemFailureRollsBackEverything() {
            long enough = api.newProduct(100, 10);
            long scarce = api.newProduct(100, 1);
            String coupon = api.newCoupon("FIXED", 10, null, null, 5);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), coupon, item(enough, 3), item(scarce, 2));

            assertProblem(res, 409, "insufficient-stock");
            assertThat(res.getBody().get("productId").asLong()).isEqualTo(scarce);
            assertThat(api.product(enough).get("reserved").asInt()).isZero();
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-05 items 비어 있음 / 누락 -> 400 validation-failed")
        void emptyOrMissingItemsIsValidationFailed() {
            Map<String, Object> empty = Map.of("items", List.of());
            assertProblem(api.post("/api/orders", empty, "X-User-Id", user(), "Idempotency-Key", key()), 400,
                    "validation-failed");
            assertProblem(api.post("/api/orders", Map.of(), "X-User-Id", user(), "Idempotency-Key", key()), 400,
                    "validation-failed");
        }

        @ParameterizedTest(name = "quantity={0}")
        @CsvSource({ "0", "-1", "100001" })
        @DisplayName("REQ-05 quantity 범위(1~100000) 밖 -> 400 validation-failed")
        void quantityOutOfRangeIsValidationFailed(long quantity) {
            long p = api.newProduct(100, 10);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), null, item(p, quantity));

            assertProblem(res, 400, "validation-failed");
        }

        @Test
        @DisplayName("REQ-05 같은 productId 중복 -> 400 validation-failed")
        void duplicateProductIsValidationFailed() {
            long p = api.newProduct(100, 10);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), null, item(p, 1), item(p, 2));

            assertProblem(res, 400, "validation-failed");
            assertThat(api.product(p).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-05 items 51개 -> 400, 50개는 허용")
        void itemCountBoundary() {
            List<Map<String, Object>> fifty = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                fifty.add(item(api.newProduct(10, 5), 1));
            }
            List<Map<String, Object>> fiftyOne = new ArrayList<>(fifty);
            fiftyOne.add(item(api.newProduct(10, 5), 1));

            ResponseEntity<JsonNode> ok = api.post("/api/orders", Map.of("items", fifty), "X-User-Id", user(),
                    "Idempotency-Key", key());
            ResponseEntity<JsonNode> tooMany = api.post("/api/orders", Map.of("items", fiftyOne), "X-User-Id", user(),
                    "Idempotency-Key", key());

            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertProblem(tooMany, 400, "validation-failed");
        }

        @Test
        @DisplayName("REQ-05 빈 문자열 couponCode -> 400 validation-failed, null 이면 쿠폰 없음")
        void blankCouponCodeIsRejectedButNullIsFine() {
            long p = api.newProduct(100, 10);
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("items", List.of(item(p, 1)));
            blank.put("couponCode", "");
            Map<String, Object> nul = new LinkedHashMap<>();
            nul.put("items", List.of(item(p, 1)));
            nul.put("couponCode", null);

            ResponseEntity<JsonNode> rejected = api.post("/api/orders", blank, "X-User-Id", user(), "Idempotency-Key",
                    key());
            ResponseEntity<JsonNode> accepted = api.post("/api/orders", nul, "X-User-Id", user(), "Idempotency-Key",
                    key());

            assertProblem(rejected, 400, "validation-failed");
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("REQ-05 본문 JSON 오류 -> 400 malformed-request")
        void brokenBodyIsMalformed() {
            ResponseEntity<JsonNode> res = api.exchange(HttpMethod.POST, "/api/orders", "{oops", MediaType.APPLICATION_JSON,
                    "X-User-Id", user(), "Idempotency-Key", key());

            assertProblem(res, 400, "malformed-request");
        }
    }

    @Nested
    @DisplayName("REQ-07/15 필수 헤더")
    class Headers {

        private Map<String, Object> validBody() {
            return Map.of("items", List.of(item(api.newProduct(100, 10), 1)));
        }

        @Test
        @DisplayName("REQ-07 [verifier 관찰 1] X-User-Id 누락 -> 400 missing-required-header (header=X-User-Id)")
        void missingUserIdIsBadRequest() {
            ResponseEntity<JsonNode> res = api.post("/api/orders", validBody(), "Idempotency-Key", key());

            assertProblem(res, 400, "missing-required-header");
            assertThat(res.getBody().get("header").asText()).isEqualTo("X-User-Id");
        }

        @Test
        @DisplayName("REQ-15 Idempotency-Key 누락 -> 400 missing-required-header (header=Idempotency-Key)")
        void missingIdempotencyKeyIsBadRequest() {
            ResponseEntity<JsonNode> res = api.post("/api/orders", validBody(), "X-User-Id", user());

            assertProblem(res, 400, "missing-required-header");
            assertThat(res.getBody().get("header").asText()).isEqualTo("Idempotency-Key");
        }

        @Test
        @DisplayName("REQ-15 두 헤더 모두 누락 -> X-User-Id 가 먼저 보고된다")
        void bothHeadersMissingReportsUserIdFirst() {
            ResponseEntity<JsonNode> res = api.post("/api/orders", validBody());

            assertProblem(res, 400, "missing-required-header");
            assertThat(res.getBody().get("header").asText()).isEqualTo("X-User-Id");
        }

        @Test
        @DisplayName("REQ-15 [verifier 관찰 1] 헤더 누락 + 본문 오류 동시 -> 400 validation-failed (본문 검증 우선)")
        void missingHeadersWithInvalidBodyReportsValidationFirst() {
            ResponseEntity<JsonNode> res = api.post("/api/orders", Map.of("items", List.of()));

            assertProblem(res, 400, "validation-failed");
        }

        @Test
        @DisplayName("REQ-07 공백뿐인 X-User-Id -> 400 missing-required-header")
        void blankUserIdIsBadRequest() {
            ResponseEntity<JsonNode> res = api.post("/api/orders", validBody(), "X-User-Id", "   ", "Idempotency-Key",
                    key());

            assertProblem(res, 400, "missing-required-header");
        }

        @Test
        @DisplayName("REQ-07 X-User-Id 65자 -> 400 validation-failed, 64자는 허용")
        void userIdLengthBoundary() {
            ResponseEntity<JsonNode> tooLong = api.post("/api/orders", validBody(), "X-User-Id", "u".repeat(65),
                    "Idempotency-Key", key());
            ResponseEntity<JsonNode> ok = api.post("/api/orders", validBody(), "X-User-Id", "u".repeat(64),
                    "Idempotency-Key", key());

            assertProblem(tooLong, 400, "validation-failed");
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("REQ-07 Idempotency-Key 129자 -> 400 validation-failed, 128자는 허용")
        void idempotencyKeyLengthBoundary() {
            ResponseEntity<JsonNode> tooLong = api.post("/api/orders", validBody(), "X-User-Id", user(),
                    "Idempotency-Key", "k".repeat(129));
            ResponseEntity<JsonNode> ok = api.post("/api/orders", validBody(), "X-User-Id", user(), "Idempotency-Key",
                    "k".repeat(127) + System.nanoTime() % 10);

            assertProblem(tooLong, 400, "validation-failed");
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("REQ-06 쿠폰 적용 / 할인 계산")
    class Coupons {

        private JsonNode orderWith(String coupon, long price, long qty) {
            long p = api.newProduct(price, 100);
            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), coupon, item(p, qty));
            assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(HttpStatus.CREATED);
            return res.getBody();
        }

        @Test
        @DisplayName("REQ-06 FIXED -> value 만큼 차감, usedCount +1, couponCode 응답")
        void fixedCouponSubtractsValue() {
            String coupon = api.newCoupon("FIXED", 5000, null, null, 10);

            JsonNode o = orderWith(coupon, 30000, 1);

            assertThat(o.get("couponCode").asText()).isEqualTo(coupon);
            assertThat(o.get("subtotal").asLong()).isEqualTo(30000);
            assertThat(o.get("discount").asLong()).isEqualTo(5000);
            assertThat(o.get("totalPrice").asLong()).isEqualTo(25000);
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-06 FIXED 가 subtotal 보다 크면 subtotal 로 clamp (totalPrice=0)")
        void fixedCouponIsClampedToSubtotal() {
            String coupon = api.newCoupon("FIXED", 50000, null, null, 10);

            JsonNode o = orderWith(coupon, 3000, 1);

            assertThat(o.get("discount").asLong()).isEqualTo(3000);
            assertThat(o.get("totalPrice").asLong()).isZero();
        }

        @Test
        @DisplayName("REQ-06 FIXED 의 maxDiscountAmount 는 계산에 쓰이지 않는다 (D-14)")
        void fixedCouponIgnoresMaxDiscountAmount() {
            String coupon = api.newCoupon("FIXED", 5000, null, 1000L, 10);

            JsonNode o = orderWith(coupon, 30000, 1);

            assertThat(o.get("discount").asLong()).isEqualTo(5000);
        }

        @Test
        @DisplayName("REQ-06 RATE 10% -> floor(subtotal*10/100), 소수점 이하 내림")
        void rateCouponFloorsDiscount() {
            String coupon = api.newCoupon("RATE", 10, null, null, 10);

            JsonNode o = orderWith(coupon, 10005, 1);

            assertThat(o.get("discount").asLong()).isEqualTo(1000);
            assertThat(o.get("totalPrice").asLong()).isEqualTo(9005);
        }

        @Test
        @DisplayName("REQ-06 RATE 는 maxDiscountAmount 상한으로 제한된다")
        void rateCouponIsCappedByMaxDiscount() {
            String coupon = api.newCoupon("RATE", 50, null, 4000L, 10);

            JsonNode o = orderWith(coupon, 20000, 1);

            assertThat(o.get("discount").asLong()).isEqualTo(4000);
            assertThat(o.get("totalPrice").asLong()).isEqualTo(16000);
        }

        @Test
        @DisplayName("REQ-06 RATE 100% 는 전액 할인 (discount == subtotal, totalPrice=0)")
        void fullRateCouponMakesOrderFree() {
            String coupon = api.newCoupon("RATE", 100, null, null, 10);

            JsonNode o = orderWith(coupon, 777, 3);

            assertThat(o.get("discount").asLong()).isEqualTo(2331);
            assertThat(o.get("totalPrice").asLong()).isZero();
        }

        @Test
        @DisplayName("REQ-06 없는 쿠폰 -> 404 coupon-not-found, 재고 예약 없음")
        void unknownCouponIs404() {
            long p = api.newProduct(1000, 5);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), "NO-SUCH-COUPON", item(p, 1));

            assertProblem(res, 404, "coupon-not-found");
            assertThat(api.product(p).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-06 유효기간 이전 -> 409 coupon-not-applicable reason=NOT_YET_VALID")
        void couponNotYetValid() {
            String code = ApiClient.uniq("FUT");
            api.post("/api/coupons", api.couponBody(code, "FIXED", 100, null, null, 5, "2098-01-01T00:00:00Z",
                    "2099-01-01T00:00:00Z"));
            long p = api.newProduct(1000, 5);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), code, item(p, 1));

            assertProblem(res, 409, "coupon-not-applicable");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("NOT_YET_VALID");
            assertThat(res.getBody().get("couponCode").asText()).isEqualTo(code);
            assertThat(api.product(p).get("reserved").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-06 유효기간 경과 -> 409 coupon-not-applicable reason=EXPIRED")
        void couponExpired() {
            String code = ApiClient.uniq("OLD");
            api.post("/api/coupons", api.couponBody(code, "FIXED", 100, null, null, 5, "2020-01-01T00:00:00Z",
                    "2020-02-01T00:00:00Z"));
            long p = api.newProduct(1000, 5);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), code, item(p, 1));

            assertProblem(res, 409, "coupon-not-applicable");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("REQ-06 최소 주문금액 미달 -> 409 reason=BELOW_MIN_ORDER_AMOUNT, 정확히 같으면 적용")
        void couponMinOrderAmountBoundary() {
            String coupon = api.newCoupon("FIXED", 100, 10000L, null, 5);
            long below = api.newProduct(9999, 5);
            long exact = api.newProduct(10000, 5);

            ResponseEntity<JsonNode> rejected = api.placeOrder(user(), key(), coupon, item(below, 1));
            ResponseEntity<JsonNode> accepted = api.placeOrder(user(), key(), coupon, item(exact, 1));

            assertProblem(rejected, 409, "coupon-not-applicable");
            assertThat(rejected.getBody().get("reason").asText()).isEqualTo("BELOW_MIN_ORDER_AMOUNT");
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(accepted.getBody().get("discount").asLong()).isEqualTo(100);
        }

        @Test
        @DisplayName("REQ-06 [verifier 관찰 3] 쿠폰 소진 -> 409 coupon-not-applicable reason=EXHAUSTED, 재고/사용횟수 불변")
        void exhaustedCouponIsRejectedWithExhaustedReason() {
            String coupon = api.newCoupon("FIXED", 100, null, null, 1);
            long p = api.newProduct(1000, 10);
            api.orderOk(user(), p, 1, coupon);

            ResponseEntity<JsonNode> res = api.placeOrder(user(), key(), coupon, item(p, 2));

            assertProblem(res, 409, "coupon-not-applicable");
            assertThat(res.getBody().get("reason").asText()).isEqualTo("EXHAUSTED");
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-06 쿠폰 오류 응답 후에도 같은 쿠폰의 남은 수량은 유지된다 (usedCount<total)")
        void failedOrderDoesNotConsumeCoupon() {
            String coupon = api.newCoupon("FIXED", 100, null, null, 2);
            long scarce = api.newProduct(1000, 1);

            api.placeOrder(user(), key(), coupon, item(scarce, 5));

            assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
        }
    }

    @Nested
    @DisplayName("REQ-07 주문 생성 멱등성")
    class Idempotency {

        @Test
        @DisplayName("REQ-07 같은 키 + 같은 본문 -> 기존 주문 201 + Location + Idempotent-Replayed, 예약/쿠폰 1회만")
        void sameKeySameBodyReplaysExistingOrder() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            String user = user();
            String key = key();
            ResponseEntity<JsonNode> first = api.placeOrder(user, key, coupon, item(p, 2));

            ResponseEntity<JsonNode> replay = api.placeOrder(user, key, coupon, item(p, 2));

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
            assertThat(replay.getBody()).isEqualTo(first.getBody());
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(2);
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-07 items 순서만 다른 본문은 같은 요청으로 간주되어 재생된다")
        void reorderedItemsAreTheSameRequest() {
            long a = api.newProduct(1000, 10);
            long b = api.newProduct(2000, 10);
            String user = user();
            String key = key();
            JsonNode first = api.placeOrder(user, key, null, item(a, 1), item(b, 2)).getBody();

            ResponseEntity<JsonNode> replay = api.placeOrder(user, key, null, item(b, 2), item(a, 1));

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getBody().get("id")).isEqualTo(first.get("id"));
        }

        @Test
        @DisplayName("REQ-07 같은 키 + 다른 수량 -> 409 idempotency-key-reused (idempotencyKey 확장), 예약 불변")
        void sameKeyDifferentQuantityIsConflict() {
            long p = api.newProduct(1000, 10);
            String user = user();
            String key = key();
            api.placeOrder(user, key, null, item(p, 1));

            ResponseEntity<JsonNode> res = api.placeOrder(user, key, null, item(p, 2));

            assertProblem(res, 409, "idempotency-key-reused");
            assertThat(res.getBody().get("idempotencyKey").asText()).isEqualTo(key);
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-07 같은 키 + 다른 couponCode -> 409 idempotency-key-reused")
        void sameKeyDifferentCouponIsConflict() {
            long p = api.newProduct(1000, 10);
            String coupon = api.newCoupon("FIXED", 100, null, null, 5);
            String user = user();
            String key = key();
            api.placeOrder(user, key, null, item(p, 1));

            ResponseEntity<JsonNode> res = api.placeOrder(user, key, coupon, item(p, 1));

            assertProblem(res, 409, "idempotency-key-reused");
            assertThat(api.coupon(coupon).get("usedCount").asInt()).isZero();
        }

        @Test
        @DisplayName("REQ-07 키 범위는 사용자별: 같은 키라도 다른 X-User-Id 는 별개의 주문")
        void sameKeyDifferentUsersCreateSeparateOrders() {
            long p = api.newProduct(1000, 10);
            String key = key();

            JsonNode first = api.placeOrder(user(), key, null, item(p, 1)).getBody();
            ResponseEntity<JsonNode> second = api.placeOrder(user(), key, null, item(p, 1));

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isNull();
            assertThat(second.getBody().get("id")).isNotEqualTo(first.get("id"));
            assertThat(api.product(p).get("reserved").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("REQ-07 [D-13] 실패한 요청의 키는 저장되지 않아 같은 키로 재시도하면 새로 평가된다")
        void failedRequestDoesNotBurnTheKey() {
            long p = api.newProduct(1000, 10);
            String code = ApiClient.uniq("LATE");
            String user = user();
            String key = key();
            ResponseEntity<JsonNode> failed = api.placeOrder(user, key, code, item(p, 1));
            assertProblem(failed, 404, "coupon-not-found");
            api.post("/api/coupons", api.couponBody(code, "FIXED", 100, null, null, 5, "2020-01-01T00:00:00Z",
                    "2099-01-01T00:00:00Z"));

            ResponseEntity<JsonNode> retry = api.placeOrder(user, key, code, item(p, 1));

            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        }

        @Test
        @DisplayName("REQ-07 멱등 재생은 주문의 현재 상태를 돌려준다 (결제 후 재생 -> PAID)")
        void replayReflectsCurrentOrderState() {
            long p = api.newProduct(1000, 10);
            String user = user();
            String key = key();
            long id = api.placeOrder(user, key, null, item(p, 1)).getBody().get("id").asLong();
            api.payOk(id);

            ResponseEntity<JsonNode> replay = api.placeOrder(user, key, null, item(p, 1));

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getBody().get("status").asText()).isEqualTo("PAID");
            assertThat(api.product(p).get("reserved").asInt()).isZero();
            assertThat(api.product(p).get("stock").asInt()).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("REQ-08 주문 단건 조회")
    class GetOrder {

        @Test
        @DisplayName("REQ-08 조회 -> 주문 스키마 전체 필드, 생성 응답과 동일")
        void getReturnsFullOrder() {
            long p = api.newProduct(1500, 10);
            String coupon = api.newCoupon("FIXED", 500, null, null, 5);
            JsonNode created = api.orderOk(user(), p, 2, coupon);

            ResponseEntity<JsonNode> res = api.get("/api/orders/" + created.get("id").asLong());

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isEqualTo(created);
            for (String field : List.of("id", "userId", "status", "items", "couponCode", "subtotal", "discount",
                    "totalPrice", "createdAt", "expiresAt", "paidAt")) {
                assertThat(res.getBody().has(field)).as(field).isTrue();
            }
        }

        @Test
        @DisplayName("REQ-08/16 기본 ORDER_PAYMENT_TTL(PT15M) -> expiresAt - createdAt = 15분")
        void expiresAtIsCreatedAtPlusDefaultFifteenMinutes() {
            long p = api.newProduct(1000, 10);

            JsonNode o = api.orderOk(p, 1);

            Duration ttl = Duration.between(Instant.parse(o.get("createdAt").asText()),
                    Instant.parse(o.get("expiresAt").asText()));
            assertThat(ttl).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("REQ-08 없는 주문 -> 404 order-not-found (orderId 확장)")
        void unknownOrderIs404() {
            ResponseEntity<JsonNode> res = api.get("/api/orders/987654321");

            assertProblem(res, 404, "order-not-found");
            assertThat(res.getBody().get("orderId").asLong()).isEqualTo(987654321L);
        }

        @Test
        @DisplayName("REQ-08/15 [verifier 관찰 2] 숫자가 아닌 주문 id -> 400 invalid-parameter, parameter='id'")
        void nonNumericIdIsInvalidParameter() {
            ResponseEntity<JsonNode> res = api.get("/api/orders/not-a-number");

            assertProblem(res, 400, "invalid-parameter");
            assertThat(res.getBody().get("parameter").asText()).isEqualTo("id");
        }

        @Test
        @DisplayName("REQ-08 Long 범위를 넘는 id -> 400 invalid-parameter")
        void overflowingIdIsInvalidParameter() {
            ResponseEntity<JsonNode> res = api.get("/api/orders/99999999999999999999");

            assertProblem(res, 400, "invalid-parameter");
        }
    }
}
