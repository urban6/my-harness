package com.example.order.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("R5.6·R7.3 PG 연결 실패")
class PaymentGatewayClientTest {

    private static PaymentGatewayClient clientForClosedPort() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return new PaymentGatewayClient(
                new PaymentGatewayProperties("http://localhost:" + port, Duration.ofSeconds(2)), new ObjectMapper());
    }

    @Test
    @DisplayName("결제 요청 연결 실패는 PaymentGatewayUnavailableException")
    void payConnectionRefused() throws Exception {
        PaymentGatewayClient client = clientForClosedPort();
        Instant start = Instant.now();
        assertThatThrownBy(() -> client.pay("key-1", 1L, 1_000L, "tok"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("환불 요청 연결 실패는 PaymentGatewayUnavailableException")
    void refundConnectionRefused() throws Exception {
        PaymentGatewayClient client = clientForClosedPort();
        assertThatThrownBy(() -> client.refund("pay_1")).isInstanceOf(PaymentGatewayUnavailableException.class);
    }
}
