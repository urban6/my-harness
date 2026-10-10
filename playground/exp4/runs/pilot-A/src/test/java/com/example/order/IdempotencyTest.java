package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R4. 멱등성 (주문 생성 · 결제) */
class IdempotencyTest extends IntegrationTestBase {

    @Test
    @DisplayName("R4.2 같은 키·같은 요청이면 최초 응답(상태·본문·Location)을 재생하고 다시 처리하지 않는다")
    void createReplaysFirstResponse() {
        long p = product(1000, 10);
        String key = "k-" + uniq();

        Res first = createOrder("u1", key, items(p, 3), null);
        Res second = createOrder("u1", key, items(p, 3), null);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("R4.2 재생 응답은 이후 주문 상태가 바뀌어도 최초 응답 그대로다")
    void replayIsFrozen() {
        long p = product(1000, 10);
        String key = "k-" + uniq();
        Res first = createOrder("u1", key, items(p, 1), null);
        long id = first.json().get("id").asLong();
        assertThat(pay(id).status()).isEqualTo(200);

        Res replay = createOrder("u1", key, items(p, 1), null);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.json().get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.3 같은 키로 다른 요청(다른 본문·다른 사용자)이면 422 IDEMPOTENCY_KEY_MISMATCH")
    void createMismatch() {
        long p = product(1000, 10);
        String key = "k-" + uniq();
        assertThat(createOrder("u1", key, items(p, 1), null).status()).isEqualTo(201);

        Res otherBody = createOrder("u1", key, items(p, 2), null);
        Res otherUser = createOrder("u2", key, items(p, 1), null);

        assertThat(otherBody.status()).isEqualTo(422);
        assertThat(otherBody.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(otherUser.status()).isEqualTo(422);
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.3 불일치(422)는 404·409 판정보다 먼저")
    void mismatchBeatsNotFoundAndConflict() {
        long p = product(1000, 10);
        String key = "k-" + uniq();
        assertThat(createOrder("u1", key, items(p, 1), null).status()).isEqualTo(201);

        assertThat(createOrder("u1", key, items(987654321L, 1), null).status()).isEqualTo(422);
        assertThat(createOrder("u1", key, items(p, 500), null).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("R4.1 키 공간은 주문 생성과 결제가 서로 독립이다")
    void keySpacesAreIndependent() {
        long p = product(1000, 10);
        String key = "shared-" + uniq();
        Res created = createOrder("u1", key, items(p, 1), null);
        long id = created.json().get("id").asLong();

        Res paid = pay(id, "tok_ok", key);

        assertThat(created.status()).isEqualTo(201);
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.json().get("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("R4.1 주문 생성·결제 모두 Idempotency-Key가 필수(없으면 400)")
    void keyRequired() {
        long p = product(1000, 10);
        long id = order(p, 1).get("id").asLong();

        Res noKeyPay = post("/api/orders/" + id + "/pay", Map.of("cardToken", "tok"));

        assertThat(noKeyPay.status()).isEqualTo(400);
        assertThat(noKeyPay.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(orderOf(id).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.2 결제도 같은 키·같은 요청이면 PG를 다시 부르지 않고 최초 응답을 재생한다")
    void payReplays() {
        long p = product(1000, 10);
        long id = order(p, 2).get("id").asLong();
        String key = "pay-" + uniq();

        Res first = pay(id, "tok_ok", key);
        Res second = pay(id, "tok_ok", key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(8);
    }

    @Test
    @DisplayName("R4.3 결제: 같은 키로 다른 주문·다른 카드 토큰이면 422")
    void payMismatch() {
        long p = product(1000, 10);
        long id1 = order(p, 1).get("id").asLong();
        long id2 = order(p, 1).get("id").asLong();
        String key = "pay-" + uniq();
        assertThat(pay(id1, "tok_a", key).status()).isEqualTo(200);

        Res otherToken = pay(id1, "tok_b", key);
        Res otherOrder = pay(id2, "tok_a", key);

        assertThat(otherToken.status()).isEqualTo(422);
        assertThat(otherToken.code()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(otherOrder.status()).isEqualTo(422);
        assertThat(orderOf(id2).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("R4.4 오류 응답은 저장되지 않아 같은 키로 다시 시도할 수 있다(주문 생성)")
    void errorsAreNotStoredForCreate() {
        long p = product(1000, 2);
        String key = "k-" + uniq();

        Res tooMany = createOrder("u1", key, items(p, 5), null);
        assertThat(tooMany.status()).isEqualTo(409);

        // 같은 키, 같은 요청: 여전히 부족하면 같은 오류(재생이 아니라 재처리)
        assertThat(createOrder("u1", key, items(p, 5), null).status()).isEqualTo(409);
        // 재고가 생기면 같은 키로 성공 — 다른 요청이어도 422가 아니라 새로 처리된다.
        Res fixed = createOrder("u1", key, items(p, 2), null);
        assertThat(fixed.status()).isEqualTo(201);
        assertThat(createOrder("u1", key, items(p, 2), null).raw()).isEqualTo(fixed.raw());
    }

    @Test
    @DisplayName("R4.4 PG 장애(503)로 끝난 키는 같은 요청으로 재시도할 수 있고, PG에는 같은 키가 전달된다(R5.3)")
    void errorsAreNotStoredForPay() {
        long p = product(1000, 10);
        long id = order(p, 1).get("id").asLong();
        String key = "pay-" + uniq();

        GATEWAY.mode(FakeGateway.Mode.SERVER_ERROR);
        Res down = pay(id, "tok_ok", key);
        assertThat(down.status()).isEqualTo(503);

        GATEWAY.mode(FakeGateway.Mode.NORMAL);
        Res retry = pay(id, "tok_ok", key);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(GATEWAY.charges()).hasSize(2);
        assertThat(GATEWAY.charges()).allSatisfy(c -> assertThat(c.headers().get("idempotency-key")).isEqualTo(key));
        assertThat(pay(id, "tok_ok", key).raw()).isEqualTo(retry.raw());
        assertThat(GATEWAY.charges()).hasSize(2);
    }

    @Test
    @DisplayName("R4.5 같은 키 동시 요청 20건: 실제 처리는 한 번, 나머지는 재생 또는 409 IDEMPOTENCY_IN_PROGRESS")
    void concurrentSameKeyCreate() {
        long p = product(1000, 100);
        String key = "k-" + uniq();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> createOrder("u1", key, items(p, 1), null));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 201) + count(results, 409)).isEqualTo(20);
        assertThat(count(results, 201)).isGreaterThanOrEqualTo(1);
        results.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
        assertThat(results.stream().filter(r -> r.status() == 201).map(Res::raw).distinct()).hasSize(1);
        assertThat(productOf(p).get("reserved").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4.5 같은 키 동시 결제 10건: PG 결제 요청은 한 번뿐")
    void concurrentSameKeyPay() {
        long p = product(1000, 10);
        long id = order(p, 1).get("id").asLong();
        String key = "pay-" + uniq();
        GATEWAY.delay(300);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> pay(id, "tok_ok", key));
        }

        List<Res> results = concurrently(tasks);

        assertThat(count(results, 200) + count(results, 409)).isEqualTo(10);
        assertThat(count(results, 200)).isGreaterThanOrEqualTo(1);
        assertThat(GATEWAY.charges()).hasSize(1);
        assertThat(productOf(p).get("stock").asLong()).isEqualTo(9);
    }
}
