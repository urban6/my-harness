package com.example.order.support;

import static com.example.order.support.HttpTestClient.headers;

import com.example.order.support.HttpTestClient.Resp;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 베이스: Testcontainers PostgreSQL(싱글턴) + 가짜 PG + 랜덤 포트 서버.
 * 컨텍스트가 여러 개(TTL 설정이 다른 클래스)여도 같은 DB 를 쓰므로 테스트 데이터는 항상 고유값(랜덤 userId/쿠폰 코드)을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakePaymentGateway PG = new FakePaymentGateway();

    static {
        POSTGRES.start();
        PG.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.gateway.url", PG::baseUrl);
    }

    @LocalServerPort
    protected int port;

    protected HttpTestClient api;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void initClient() {
        api = new HttpTestClient("http://localhost:" + port);
        PG.reset();
    }

    // ---- 픽스처 헬퍼 ----

    protected static String uid() {
        return "u-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String idemKey() {
        return "k-" + UUID.randomUUID();
    }

    protected static String couponCode() {
        // [A-Z0-9]{4,20}
        return "C" + Long.toString(System.nanoTime(), 36).toUpperCase() + SEQ.incrementAndGet();
    }

    protected long createProduct(long price, long stock) {
        Resp r = api.post("/api/products",
                "{\"name\":\"item\",\"price\":" + price + ",\"stock\":" + stock + "}");
        if (r.status() != 201) {
            throw new AssertionError("create product failed: " + r.status() + " " + r.body());
        }
        return r.json().get("id").asLong();
    }

    protected String createCoupon(String type, long value, Long maxDiscount, long totalQuantity) {
        String code = couponCode();
        String max = maxDiscount == null ? "null" : maxDiscount.toString();
        Resp r = api.post("/api/coupons", "{\"code\":\"" + code + "\",\"type\":\"" + type + "\",\"value\":" + value
                + ",\"minOrderAmount\":0,\"maxDiscountAmount\":" + max + ",\"totalQuantity\":" + totalQuantity
                + ",\"validFrom\":\"2020-01-01T00:00:00Z\",\"validUntil\":\"2099-01-01T00:00:00+09:00\"}");
        if (r.status() != 201) {
            throw new AssertionError("create coupon failed: " + r.status() + " " + r.body());
        }
        return code;
    }

    protected Resp placeOrder(String userId, String key, long productId, long quantity, String couponCode) {
        String coupon = couponCode == null ? "null" : "\"" + couponCode + "\"";
        return api.post("/api/orders",
                "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}],\"couponCode\":" + coupon + "}",
                headers("X-User-Id", userId, "Idempotency-Key", key));
    }

    protected Resp pay(long orderId, String key, String cardToken) {
        return api.post("/api/orders/" + orderId + "/pay", "{\"cardToken\":\"" + cardToken + "\"}",
                headers("Idempotency-Key", key));
    }
}
