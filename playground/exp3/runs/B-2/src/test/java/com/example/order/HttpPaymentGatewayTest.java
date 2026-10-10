package com.example.order;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.example.order.common.error.DomainException;
import com.example.order.payment.HttpPaymentGateway;
import com.example.order.payment.PaymentGateway.ChargeResult;
import com.example.order.payment.PaymentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPaymentGatewayTest {

    private HttpServer server;
    private HttpPaymentGateway gateway;
    private final AtomicReference<String> chargeBody = new AtomicReference<>();
    private final AtomicReference<String> chargeKey = new AtomicReference<>();
    private volatile String chargeResponse = "{\"paymentId\":\"p-1\",\"status\":\"APPROVED\"}";
    private volatile int chargeStatus = 200;
    private volatile String refundResponse = "{\"paymentId\":\"p-1\",\"status\":\"REFUNDED\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean refund = path.equals("/v1/payments/p-1/refund");
            if (!refund) {
                chargeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                chargeKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            }
            byte[] out = (refund ? refundResponse : chargeResponse).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(refund ? 200 : chargeStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new HttpPaymentGateway(new PaymentProperties(url, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                RestClient.builder());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void charge_sendsContractRequest_andParsesApproval() {
        ChargeResult result = gateway.charge(7, 12000, "tok_x", "idem-1");

        assertThat(result).isEqualTo(new ChargeResult("p-1", true));
        assertThat(chargeKey.get()).isEqualTo("idem-1");
        assertThat(chargeBody.get()).contains("\"orderId\":7", "\"amount\":12000", "\"cardToken\":\"tok_x\"");
    }

    @Test
    void charge_parsesDecline() {
        chargeResponse = "{\"paymentId\":\"p-2\",\"status\":\"DECLINED\"}";
        assertThat(gateway.charge(1, 100, "t", "k")).isEqualTo(new ChargeResult("p-2", false));
    }

    @Test
    void charge_mapsServerErrorAndGarbageToUpstreamError() {
        chargeStatus = 500;
        assertThatThrownBy(() -> gateway.charge(1, 100, "t", "k")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).kind()).isEqualTo(DomainException.Kind.UPSTREAM);

        chargeStatus = 200;
        chargeResponse = "{\"paymentId\":\"p-3\",\"status\":\"WEIRD\"}";
        assertThatThrownBy(() -> gateway.charge(1, 100, "t", "k")).isInstanceOf(DomainException.class);
    }

    @Test
    void refund_succeedsOnRefundedStatus_andFailsOtherwise() {
        gateway.refund("p-1");

        refundResponse = "{\"paymentId\":\"p-1\",\"status\":\"FAILED\"}";
        assertThatThrownBy(() -> gateway.refund("p-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void charge_mapsConnectionFailureToUpstreamError() {
        server.stop(0);
        assertThatThrownBy(() -> gateway.charge(1, 100, "t", "k")).isInstanceOf(DomainException.class);
    }
}
